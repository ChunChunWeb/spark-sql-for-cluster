#!/bin/bash
# Copyright◎2023, Huangyichun. All rights reserved.
#
# This program start a spark-sql session to yarn cluster.
# Date: 2023-9-8
# Auth: huangyichun
# Version: 1.0






function printHelp(){
    echo "
This program start a spark-sql session to yarn cluster.
如果需要kerberos 认证，请同时使用 -k keytab.file  -p yourPricipal@name
-e & -f 请勿同时使用
  -k []     --kerberos []   添加kerberos文件, 最好为绝对路径
  -p []     --pricipal []   使用的pricipal
  -e []     --execute  []   -e执行某个简短sql
  -f []     --filesql  []   -f指定某个sql文件, 最好为绝对路径
  -n []     --nameyarn []   如果需要执行在yarn上的任务名称
  -h        --help          打印帮助信息
"
}


ARGS=`getopt -o hn:k:p:e:f: --long help,nameyarn:,keytab:,pricipal:,execute:,filesql: -n "$0" -- "$@"`

if [ $? != 0 ]; then
    echo "Terminating..."
    exit 1
fi

# 将规范化后的命令行参数分配至位置参数（$1,$2,...)
eval set -- "${ARGS}"

nameyarn="spark-sql_session_from_docker_"`hostname`
fileSql=""
executeSql=""
keytab=""
pricipal=""
keyberosCount=0
while true
do
    case "$1" in
        -h|--help)
            printHelp;
            exit 0
            ;;
        -n|--nameyarn)
            nameyarn="$2";
            shift 2
            ;;
        -k|--keytab)
            keytab="$2"
            keyberosCount+=1
            shift 2;
            ;;
        -p|--pricipal)
            pricipal="$2"
            keyberosCount+=1
            shift 2;
            ;;
        -e|--execute)
            executeSql="$2"
            shift 2;
            ;;
        -f|--filesql)
            fileSql="$2"
            shift 2;
            ;;
        --)
            shift
            break
            ;;
        *)
            echo "Internal error!"
            exit 1
            ;;
    esac
done

cmd="spark-submit --class org.apache.spark.sql.hive.cluster.SparkSqlCliClusterDriver  \
     --master yarn --deploy-mode cluster \
     --name \"${nameyarn}\" \
     spark-sql-cluster.jar "

# kerberos kinit
if [ ${keyberosCount} -eq 2 ] ; then
  echo kinit -kt "${keytab}" "${pricipal}"
  kinit -kt "${keytab}" "${pricipal}"
  [ $? -ne 0 ] && echo "kerberos kinit field, ensure keytab:${keytab} pricipal:${pricipal} is right" && exit 101
fi

efCount=0
if [ "a${fileSql}" = "a" ]; then
  cmd="${cmd} -f ${fileSql}"
  efCount+=1
fi

if [ "a${executeSql}" = "a" ]; then
  cmd="${cmd} -e ${executeSql}"
  efCount+=1
fi

if [ ${efCount} -eq 1 ]; then
    echo ${cmd} && `${cmd}`
    exit 0
else
  echo "can't -e and -f set args at same time, or not set."
  exit 102
fi






