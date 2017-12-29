---
layout: global
title: Spark on Kubernetes Integration Tests
---

# Running the Kubernetes Integration Tests

Note that the integration test framework is currently being heavily revised and
is subject to change.

Note that currently the integration tests only run with Java 8.

Integration tests firstly require installing [Minikube](https://kubernetes.io/docs/getting-started-guides/minikube/) on
your machine, and for the `Minikube` binary to be on your `PATH`.. Refer to the Minikube documentation for instructions
on how to install it. It is recommended to allocate at least 8 CPUs and 8GB of memory to the Minikube cluster.

Running the integration tests requires a Spark distribution package tarball that
contains Spark jars, submission clients, etc. You can download a tarball from
http://spark.apache.org/downloads.html. Or, you can create a distribution from
source code using `make-distribution.sh`. For example:

```
$ git clone git@github.com:apache/spark.git
$ cd spark
$ ./dev/make-distribution.sh --tgz \
     -Phadoop-2.7 -Pkubernetes -Pkinesis-asl -Phive -Phive-thriftserver
```

The above command will create a tarball like spark-2.3.0-SNAPSHOT-bin.tgz in the
top-level dir. For more details, see the related section in
[building-spark.md](https://github.com/apache/spark/blob/master/docs/building-spark.md#building-a-runnable-distribution)


Once you prepare the tarball, the integration tests can be executed with Maven or
your IDE. Note that when running tests from an IDE, the `pre-integration-test`
phase must be run every time the Spark main code changes.  When running tests
from the command line, the `pre-integration-test` phase should automatically be
invoked if the `integration-test` phase is run.

With Maven, the integration test can be run using the following command:

```
$ mvn clean integration-test  \
    -Dspark-distro-tgz=spark/spark-2.3.0-SNAPSHOT-bin.tgz
```

# Running against an arbitrary cluster

In order to run against any cluster, use the following:
```sh
$ mvn clean integration-test  \
    -Dspark-distro-tgz=spark/spark-2.3.0-SNAPSHOT-bin.tgz  \
    -DextraScalaTestArgs="-Dspark.kubernetes.test.master=k8s://https://<master> -Dspark.docker.test.driverImage=<driver-image> -Dspark.docker.test.executorImage=<executor-image>"
```

# Reuse the previous Docker images

The integration tests build a number of Docker images, which takes some time.
By default, the images are built every time the tests run.  You may want to skip
re-building those images during development, if the distribution package did not
change since the last run. You can pass the property
`spark.docker.test.skipBuildImages` to the test process.
Here is an example:

```
$ mvn clean integration-test  \
    -Dspark-distro-tgz=spark/spark-2.3.0-SNAPSHOT-bin.tgz  \
    "-Dspark.docker.test.skipBuildImages=true"
```
