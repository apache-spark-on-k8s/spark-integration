#!/bin/bash

set -ex
apt-get update && apt-get install -y curl git
git clone https://github.com/apache/spark
cd spark && ./dev/make-distribution.sh --tgz -Phadoop-2.7 -Pkubernetes -DskipTests
cd dist && ./sbin/build-push-docker-images.sh -r gcr.io/spark-k8s-test -t latest build && \
           ./sbin/build-push-docker-images.sh -r gcr.io/spark-k8s-test -t latest push
cd ../../spark-integration/integration-test

cluster=kubectl cluster-info | head -n 1 | grep -oE "https?://\b([0-9]{1,3}\.){3}[0-9]{1,3}\b"
mvn clean -Ddownload.plugin.skip=true integration-test \
          -Dspark-distro-tgz=/home/ramanathana/go-workspace/src/apache-spark-on-k8s/spark-integration/e2e/spark/*.tgz -Dspark-dockerfiles-dir=/home/ramanathana/go-workspace/src/apache-spark-on-k8s/spark-integration/e2e/spark/dist/kubernetes/dockerfiles/ \
          -DextraScalaTestArgs="-Dspark.kubernetes.test.master=k8s://$cluster -Dspark.docker.test.driverImage=gcr.io/spark-k8s-test/spark-driver:latest -Dspark.docker.test.executorImage=gcr.io/spark-k8s-test:latest"
