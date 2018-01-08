---
layout: global
title: Spark on Kubernetes Integration Tests
---

# Running the Kubernetes Integration Tests

Note that the integration test framework is currently being heavily revised and
is subject to change. Note that currently the integration tests only run with Java 8.

As shorthand to run the tests against any given cluster, you can use the `e2e/runner.sh` script.
The script assumes that you have a functioning Kubernetes cluster (1.6+) with kubectl
configured to access it. The master URL of the currently configured cluster on your
machine can be discovered as follows:

```
$ kubectl cluster-info

Kubernetes master is running at https://xyz
```

If you want to use a local [minikube](https://github.com/kubernetes/minikube) cluster,
the minimum tested version is 0.23.0, with the kube-dns addon enabled
and the recommended configuration is 3 CPUs and 4G of memory. There is also a wrapper
script for running on minikube, `e2e/e2e-minikube.sh` for testing the master branch
of the apache/spark repository in specific.

```
$ minikube start --memory 4000 --cpus 3
```

If you're using a non-local cluster, you must provide an image repository
which you have write access to, using the `-i` option, in order to store docker images
generated during the test.

Example usages of the script:

```
$ ./e2e/runner.sh -m https://xyz -i docker.io/foxish -d cloud
$ ./e2e/runner.sh -m https://xyz -i test -d minikube
$ ./e2e/runner.sh -m https://xyz -i test -r https://github.com/my-spark/spark -d minikube
$ ./e2e/runner.sh -m https://xyz -i test -r https://github.com/my-spark/spark -b my-branch -d minikube
```

# Detailed Documentation

## Running the tests using maven

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

## Running against an arbitrary cluster

In order to run against any cluster, use the following:
```sh
$ mvn clean integration-test  \
    -Dspark-distro-tgz=spark/spark-2.3.0-SNAPSHOT-bin.tgz  \
    -DextraScalaTestArgs="-Dspark.kubernetes.test.master=k8s://https://<master> -Dspark.docker.test.driverImage=<driver-image> -Dspark.docker.test.executorImage=<executor-image>"
```

## Preserve the Minikube VM

The integration tests make use of
[Minikube](https://github.com/kubernetes/minikube), which fires up a virtual
machine and setup a single-node kubernetes cluster within it. By default the vm
is destroyed after the tests are finished.  If you want to preserve the vm, e.g.
to reduce the running time of tests during development, you can pass the
property `spark.docker.test.persistMinikube` to the test process:

```
$ mvn clean integration-test  \
    -Dspark-distro-tgz=spark/spark-2.3.0-SNAPSHOT-bin.tgz  \
    -DextraScalaTestArgs=-Dspark.docker.test.persistMinikube=true
```

## Reuse the previous Docker images

The integration tests build a number of Docker images, which takes some time.
By default, the images are built every time the tests run.  You may want to skip
re-building those images during development, if the distribution package did not
change since the last run. You can pass the property
`spark.docker.test.skipBuildImages` to the test process. This will work only if
you have been setting the property `spark.docker.test.persistMinikube`, in the
previous run since the docker daemon run inside the minikube environment.  Here
is an example:

```
$ mvn clean integration-test  \
    -Dspark-distro-tgz=spark/spark-2.3.0-SNAPSHOT-bin.tgz  \
    "-DextraScalaTestArgs=-Dspark.docker.test.persistMinikube=true -Dspark.docker.test.skipBuildImages=true"
```
