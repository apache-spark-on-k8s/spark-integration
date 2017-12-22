#!/bin/bash

set -ex
apt-get update && apt-get install -y curl git
git clone https://github.com/apache/spark
cd spark && ./dev/make-distribution.sh --tgz -Phadoop-2.7 -Pkubernetes -DskipTests
cd dist && ./sbin/build-push-docker-images.sh -r gcr.io/spark-test -t latest build && \
           ./sbin/build-push-docker-images.sh -r gcr.io/spark-test -t latest push
cd ../../spark-integration/integration-test
mvn clean -Ddownload.plugin.skip=true integration-test \
          -Dspark-distro-tgz=/home/ramanathana/go-workspace/src/apache-spark-on-k8s/spark-integration/e2e/spark/*.tgz -Dspark-dockerfiles-dir=/home/ramanathana/go-workspace/src/apache-spark-on-k8s/spark-integration/e2e/spark/dist/kubernetes/dockerfiles/ \
          -DextraScalaTestArgs="-Dspark.kubernetes.test.master=k8s://https:// -Dspark.docker.test.driverImage=foxish/spark-driver:0.1 -Dspark.docker.test.executorImage=foxish/spark-executor:0.1"
