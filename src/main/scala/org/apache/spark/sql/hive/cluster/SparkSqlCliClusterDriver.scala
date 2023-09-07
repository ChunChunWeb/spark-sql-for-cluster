package org.apache.spark.sql.hive.cluster

import org.apache.commons.lang3.StringUtils
import org.apache.hadoop.hive.cli.{CliSessionState, OptionsProcessor}
import org.apache.hadoop.hive.common.HiveInterruptUtils
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.ql.session.SessionState
import org.apache.hadoop.security.{Credentials, UserGroupInformation}
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.sql.hive.HiveUtils
import org.apache.spark.sql.hive.client.HiveClientImpl
import org.apache.spark.sql.hive.security.HiveDelegationTokenProvider
import org.apache.spark.sql.hive.thriftserver._
import org.apache.spark.util.ShutdownHookManager
import org.apache.spark.{SparkConf, SparkFiles}
import org.apache.thrift.transport.TSocket

import java.io._
import java.nio.charset.StandardCharsets._
import scala.collection.JavaConverters._

object SparkSqlCliClusterDriver {
  private val prompt = "spark-sql-for-cluster"
  private val continuedPrompt = "".padTo(prompt.length, ' ')
  private var transport: TSocket = _
  private final val SPARK_HADOOP_PROP_PREFIX = "spark.hadoop."

  installSignalHandler()

  /**
   * Install an interrupt callback to cancel all Spark jobs. In Hive's CliDriver#processLine(),
   * a signal handler will invoke this registered callback if a Ctrl+C signal is detected while
   * a command is being processed by the current thread.
   */
  def installSignalHandler(): Unit = {
    HiveInterruptUtils.add(() => {
      // Handle remote execution mode
      if (SparkSQLEnv.sparkContext != null) {
        SparkSQLEnv.sparkContext.cancelAllJobs()
      } else {
        if (transport != null) {
          // Force closing of TCP connection upon session termination
          transport.getSocket.close()
        }
      }
    })
  }


  /**
   * 重新了main方法
   * 去除cluster模式报错
   *
   * @param args
   */
  def main(args: Array[String]): Unit = {
    print("sql main start")
    val logger: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(this.getClass)
    val oproc = new OptionsProcessor()
    if (!oproc.process_stage1(args)) {
      System.exit(1)
    }

    val sparkConf = new SparkConf(loadDefaults = true)
    val hadoopConf = SparkHadoopUtil.get.newConfiguration(sparkConf)
    val extraConfigs = HiveUtils.formatTimeVarsForHiveClient(hadoopConf)

    val cliConf = HiveClientImpl.newHiveConf(sparkConf, hadoopConf, extraConfigs)

    val sessionState = new CliSessionState(cliConf)

    sessionState.in = System.in
    try {
      sessionState.out = new PrintStream(System.out, true, UTF_8.name())
      sessionState.info = new PrintStream(System.err, true, UTF_8.name())
      sessionState.err = new PrintStream(System.err, true, UTF_8.name())
    } catch {
      case e: UnsupportedEncodingException =>
        System.exit(2)
    }

    if (!oproc.process_stage2(sessionState)) {
      System.exit(3)
    }


    // Set all properties specified via command line.
    val conf: HiveConf = sessionState.getConf
    sessionState.cmdProperties.entrySet().asScala.foreach { item =>
      val key = item.getKey.toString
      val value = item.getValue.toString
      // We do not propagate metastore options to the execution copy of hive.
      if (key != "javax.jdo.option.ConnectionURL") {
        conf.set(key, value)
        sessionState.getOverriddenConfigurations.put(key, value)
      }
    }

    val tokenProvider = new HiveDelegationTokenProvider()
    if (tokenProvider.delegationTokensRequired(sparkConf, hadoopConf)) {
      val credentials = new Credentials()
      tokenProvider.obtainDelegationTokens(hadoopConf, sparkConf, credentials)
      UserGroupInformation.getCurrentUser.addCredentials(credentials)
    }

    SessionState.start(sessionState)

    // Clean up after we exit
    ShutdownHookManager.addShutdownHook { () => SparkSQLEnv.stop() }

    val remoteMode = isRemoteMode(sessionState)
    // "-h" option has been passed, so connect to Hive thrift server.
    if (!remoteMode) {
      // Hadoop-20 and above - we need to augment classpath using hiveconf
      // components.
      // See also: code in ExecDriver.java
      var loader = conf.getClassLoader
      val auxJars = HiveConf.getVar(conf, HiveConf.ConfVars.HIVEAUXJARS)
      if (StringUtils.isNotBlank(auxJars)) {
        val resourceLoader = SparkSQLEnv.sqlContext.sessionState.resourceLoader
        StringUtils.split(auxJars, ",").foreach(resourceLoader.addJar(_))
        //        loader = Utilities.addToClassPath(loader, StringUtils.split(auxJars, ","))
      }
      conf.setClassLoader(loader)
      Thread.currentThread().setContextClassLoader(loader)
    } else {
      // Hive 1.2 + not supported in CLI
      throw new RuntimeException("Remote operations not supported")
    }
    // Respect the configurations set by --hiveconf from the command line
    // (based on Hive's CliDriver).
    val hiveConfFromCmd = sessionState.getOverriddenConfigurations.entrySet().asScala
    val newHiveConf = hiveConfFromCmd.map { kv =>
      // If the same property is configured by spark.hadoop.xxx, we ignore it and
      // obey settings from spark properties
      val k = kv.getKey
      val v = sys.props.getOrElseUpdate(SPARK_HADOOP_PROP_PREFIX + k, kv.getValue)
      (k, v)
    }

    val cli = new SparkSQLCLIDriver
    cli.setHiveVariables(oproc.getHiveVariables)

    // TODO work around for set the log output to console, because the HiveContext
    // will set the output into an invalid buffer.
    sessionState.in = System.in
    try {
      sessionState.out = new PrintStream(System.out, true, UTF_8.name())
      sessionState.info = new PrintStream(System.err, true, UTF_8.name())
      sessionState.err = new PrintStream(System.err, true, UTF_8.name())
    } catch {
      case e: UnsupportedEncodingException => System.exit(4)
    }

    // We don't propagate hive.metastore.warehouse.dir, because it might has been adjusted in
    // [[SharedState.loadHiveConfFile]] based on the user specified or default values of
    // spark.sql.warehouse.dir and hive.metastore.warehouse.dir.
    for ((k, v) <- newHiveConf if k != "hive.metastore.warehouse.dir") {
      SparkSQLEnv.sqlContext.setConf(k, v)
    }

    print("sessionState main start")


    if (sessionState.database != null) {
      SparkSQLEnv.sqlContext.sql(s"USE ${sessionState.database}")
    }

    // Execute -i init files (always in silent mode)
    cli.processInitFiles(sessionState)

    newHiveConf.foreach { kv =>
      SparkSQLEnv.sqlContext.setConf(kv._1, kv._2)
    }


    SparkSQLEnv.sparkContext.setLogLevel("Debug")

    cli.printMasterAndAppId()

    // for -e
    val ret: Int = if (sessionState.execString != null) {
      logger.info(s"use -e sql")
      cli.processLine(sessionState.execString)
    } else {
      // for -f
      try {
        if (sessionState.fileName != null) {
          // deploy-mod must be cluster
          val hdfsPath = System.getenv("SPARK_YARN_STAGING_DIR") + "/" + getFileName(sessionState.fileName)
          logger.info(s"use -f file. fileName: ${sessionState.fileName}, hdfsPath : $hdfsPath")
          cli.processFile(hdfsPath)
        } else {
          logger.error(s"at least one args need: -e or -f")
          101
        }
      } catch {
        case e: FileNotFoundException =>
          logger.error(s"Could not open input file for reading. if use -f, plz use --files to update the file. (${e.getMessage})")
          102
        case e: Throwable =>
          logger.error(s"error when exec MySparkSQLCLIDriver. (${e.getMessage})")
          103
      }
    }
    sessionState.close()
    if (ret != 0) {
      throw new RuntimeException(s"MySparkSQLCLIDriver exit with code($ret)")
    }
  }

  def getFileName(path: String): String = {
    val strings: Array[String] = path.split("[/\\\\]")

    strings(strings.length - 1)
  }

  def isRemoteMode(state: CliSessionState): Boolean = {
    //    sessionState.isRemoteMode
    state.isHiveServerQuery
  }
}
