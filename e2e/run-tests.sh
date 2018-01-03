#!/bin/bash

set -ex
<<<<<<< HEAD
echo "deb http://http.debian.net/debian jessie-backports main" >> /etc/apt/sources.list
apt-get update && apt-get install -y curl wget git tar openjdk-8-jdk

wget http://www-eu.apache.org/dist/maven/maven-3/3.5.2/binaries/apache-maven-3.5.2-bin.tar.gz
tar xvf apache-maven-3.5.2-bin.tar.gz
root=$(pwd)
export PATH=$root/apache-maven-3.5.2:$PATH

git clone https://github.com/apache/spark
git clone https://github.com/apache-spark-on-k8s/spark-integration
cd spark && ./dev/make-distribution.sh --tgz -Phadoop-2.7 -Pkubernetes -DskipTests
tag=(git rev-parse HEAD | cut -c -6)

cd dist && ./sbin/build-push-docker-images.sh -r gcr.io/swarm-1358 -t $tag build && \
           gcloud docker -- push gcr.io/swarm-1358/spark-driver:$tag && \
           gcloud docker -- push gcr.io/swarm-1358/spark-executor:$tag && \
           gcloud docker -- push gcr.io/swarm-1358/spark-init:$tag

cd ../../spark-integration/integration-test

cluster=kubectl cluster-info | head -n 1 | grep -oE "https?://\b([0-9]{1,3}\.){3}[0-9]{1,3}\b"
mvn clean -Ddownload.plugin.skip=true integration-test \
          -Dspark-distro-tgz=$root/spark/*.tgz \
          -DextraScalaTestArgs="-Dspark.kubernetes.test.master=k8s://$cluster -Dspark.docker.test.driverImage=gcr.io/spark-k8s-test/spark-driver:latest -Dspark.docker.test.executorImage=gcr.io/spark-k8s-test:latest"
