
# Spark Sql on Yarn Cluster
总所周知在 spark sql on yarn 模式中，部署模式必须使用 client进行。
但在某些地方比如容器内部不支持client进行任务提交，必须使用cluster。
所以诞生了这个代码。

# How to use
- 下载该代码，并将 <spark.version> &  <scala.version> 更改为自己集群的即可。
- 打包为jar，使用 spark-submit 进行任务提交
- 具体提交请看代码中的 shell 脚本

# what do the code do
查看 spark-sql shell 代码可知，spark-sql实现其实也是使用 spark-submit 运行了一个特定的包。
那么我们也可以仿照这个 class 进行开发，删除不需要的交互模式，保留需要的 -e & -f

```shell
exec "${SPARK_HOME}"/bin/spark-submit --class \
org.apache.spark.sql.hive.thriftserver.SparkSQLCLIDriver "$@"
```


## last update time
2023-9-7 16:01:47


## 参考
kazke https://juejin.cn/post/7056334152408236046