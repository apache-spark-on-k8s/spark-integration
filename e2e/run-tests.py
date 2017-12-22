#!/usr/bin/env python
import subprocess
import os

def clone_repo():
  # print subprocess.check_output(['git', 'clone', 'https://github.com/apache/spark'])
  # print subprocess.check_output(['git', 'clone', 'https://github.com/apache-spark-on-k8s/spark-integration'])
  os.chdir("spark")
  # print subprocess.check_output(['./dev/make-distribution.sh', '--name', 'head-spark' '--tgz', '-Phadoop-2.7', '-Pkubernetes', '-DskipTests'])

  os.chdir("dist")
  # print subprocess.check_output(['./sbin/build-push-docker-images.sh', '-r', 'foxish', '-t', '0.1', 'build'])
  # print subprocess.check_output(['./sbin/build-push-docker-images.sh', '-r', 'foxish', '-t', '0.1', 'push'])

  os.chdir("../../spark-integration")
  print subprocess.check_output(['mvn', 'clean', 'integration-test',
                                 '-Ddownload.plugin.skip=true',
                                 '-Dspark-distro-tgz=../spark/',
                                 '-DextraScalaTestArgs="-Dspark.kubernetes.test.master=k8s://https://35.197.21.13 -Dspark.docker.test.driverImage=foxish/spark-driver:0.1 -Dspark.docker.test.executorImage=foxish/spark-executor:0.1"'])

"""
mvn clean -Ddownload.plugin.skip=true integration-test  \
-Dspark-distro-tgz=/home/ramanathana/go-workspace/src/apache-spark-on-k8s/release/spark/dist/spark.tar.gz  \
-Dspark-dockerfiles-dir=/home/ramanathana/go-workspace/src/apache-spark-on-k8s/release/spark/dist/kubernetes/dockerfiles \
-DextraScalaTestArgs="-Dspark.kubernetes.test.master=k8s://https://... -Dspark.docker.test.driverImage=spark-driver -Dspark.docker.test.executorImage=spark-executor"
"""




def main():
  clone_repo()



if __name__ == "__main__":
  main()