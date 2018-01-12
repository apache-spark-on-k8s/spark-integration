---
layout: global
title: Spark on Kubernetes Integration Tests
---

# Running the Kubernetes Integration Tests

Note that the integration test framework is currently being heavily revised and
is subject to change. Note that currently the integration tests only run with Java 8.

The simplest way to run the integration tests is to install and run Minikube, then run the following:

    build/mvn integration-test

The minimum tested version of Minikube is 0.23.0. The kube-dns addon must be enabled. Minikube should
run with a minimum of 3 CPUs and 4G of memory:

    minikube start --cpus 3 --memory 4G

You can download Minikube [here](https://github.com/kubernetes/minikube/releases).

# Integration test customization

Configuration of the integration test runtime is done through passing different Java system properties to the Maven
command. The main useful options are outlined below.

## Use a non-local cluster

To use your own cluster running in the cloud, set the following:

* `spark.kubernetes.test.deployMode` to `cloud` to indicate that Minikube will not be used.
* `spark.kubernetes.test.master` to your cluster's externally accessible URL
* `spark.kubernetes.test.imageRepo` to a write-accessible Docker image repository that provides the images for your
cluster. The framework assumes your local Docker client can push to this repository.

Therefore the command looks like this:

    build/mvn integration-test \
      -Dspark.kubernetes.test.deployMode=cloud \
      -Dspark.kubernetes.test.master=https://example.com:8443/apiserver \
      -Dspark.kubernetes.test.repo=docker.example.com/spark-images

## Re-using Docker Images

By default, the test framework will build new Docker images on every test execution. A unique image tag is generated,
and it is written to file at `target/imageTag.txt`. To reuse the images built in a previous run, set:

* `spark.kubernetes.test.imageTag` to the tag specified in `target/imageTag.txt`
* `spark.kubernetes.test.skipBuildingImages` to `true`

Therefore the command looks like this:

    build/mvn integration-test \
      -Dspark.kubernetes.test.imageTag=$(cat target/imageTag.txt) \
      -Dspark.kubernetes.test.skipBuildingImages=true

## Customizing the Spark Source Code to Test

By default, the test framework will test the master branch of Spark from [here](https://github.com/apache/spark). You
can specify the following options to test against different source versions of Spark:

* `spark.kubernetes.test.sparkRepo` to the git or http URI of the Spark git repository to clone
* `spark.kubernetes.test.sparkBranch` to the branch of the repository to build.

An example:

    build/mvn integration-test \
      -Dspark.kubernetes.test.sparkRepo=https://github.com/apache-spark-on-k8s/spark \
      -Dspark.kubernetes.test.sparkBranch=new-feature

Additionally, you can use a pre-built Spark distribution. In this case, the repository is not cloned at all, and no
source code has to be compiled.

* `spark.kubernetes.test.sparkTgz` can be set to a tarball containing the Spark distribution to test.

When the tests are cloning a repository and building it, the Spark distribution is placed in
`target/spark/spark-<VERSION>.tgz`. Reuse this tarball to save a significant amount of time if you are iterating on
the development of these integration tests.
