/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.deploy.k8s.integrationtest

import java.io.{File, FileOutputStream}
import java.nio.file.Paths
import java.util.{Properties, UUID}

import com.google.common.base.Charsets
import com.google.common.io.Files
import io.fabric8.kubernetes.client.internal.readiness.Readiness
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FunSuite}
import org.scalatest.concurrent.{Eventually, PatienceConfiguration}
import org.scalatest.time.{Minutes, Seconds, Span}
import scala.collection.JavaConverters._

import org.apache.spark.deploy.k8s.integrationtest.backend.IntegrationTestBackendFactory
import org.apache.spark.deploy.k8s.integrationtest.constants.MINIKUBE_TEST_BACKEND

private[spark] class KubernetesSuite extends FunSuite with BeforeAndAfterAll with BeforeAndAfter {
  import KubernetesSuite._
  private val testBackend = IntegrationTestBackendFactory.getTestBackend()
  private val APP_LOCATOR_LABEL = UUID.randomUUID().toString.replaceAll("-", "")
  private var kubernetesTestComponents: KubernetesTestComponents = _
  private var testAppConf: TestAppConf = _
  private var staticAssetServerLauncher: StaticAssetServerLauncher = _

  override def beforeAll(): Unit = {
    testBackend.initialize()
    kubernetesTestComponents = new KubernetesTestComponents(testBackend.getKubernetesClient)
    staticAssetServerLauncher = new StaticAssetServerLauncher(
      kubernetesTestComponents.kubernetesClient.inNamespace(kubernetesTestComponents.namespace))
  }

  override def afterAll(): Unit = {
    testBackend.cleanUp()
  }

  before {
    testAppConf = kubernetesTestComponents.newTestJobConf()
      .set("spark.kubernetes.initcontainer.docker.image", "spark-init:latest")
      .set("spark.kubernetes.driver.docker.image", "spark-driver:latest")
      .set("spark.kubernetes.driver.label.spark-app-locator", APP_LOCATOR_LABEL)
    kubernetesTestComponents.createNamespace()
  }

  after {
    kubernetesTestComponents.deleteNamespace()
  }

  test("Use container-local resources without the resource staging server") {
    assume(testBackend.name == MINIKUBE_TEST_BACKEND)

    testAppConf.setJars(Seq(CONTAINER_LOCAL_HELPER_JAR_PATH))
    runSparkPiAndVerifyCompletion(CONTAINER_LOCAL_MAIN_APP_RESOURCE)
  }

  test("Use remote resources without the resource staging server.") {
    assume(testBackend.name == MINIKUBE_TEST_BACKEND)
    val assetServerUri = staticAssetServerLauncher.launchStaticAssetServer()
    testAppConf.setJars(Seq(
      s"$assetServerUri/${EXAMPLES_JAR_FILE.getName}",
      s"$assetServerUri/${HELPER_JAR_FILE.getName}"
    ))
    runSparkPiAndVerifyCompletion()
  }

  test("Submit small local files without the resource staging server.") {
    assume(testBackend.name == MINIKUBE_TEST_BACKEND)
    testAppConf.setJars(Seq(CONTAINER_LOCAL_HELPER_JAR_PATH))
    val testExistenceFileTempDir = Files.createTempDir()
    testExistenceFileTempDir.deleteOnExit()
    val testExistenceFile = new File(testExistenceFileTempDir, "input.txt")
    Files.write(TEST_EXISTENCE_FILE_CONTENTS, testExistenceFile, Charsets.UTF_8)
    testAppConf.set("spark.files", testExistenceFile.getAbsolutePath)
    runSparkApplicationAndVerifyCompletion(
      CONTAINER_LOCAL_MAIN_APP_RESOURCE,
      FILE_EXISTENCE_MAIN_CLASS,
      Seq(
        s"File found at /opt/spark/work-dir/${testExistenceFile.getName} with correct contents.",
        s"File found on the executors at the relative path ${testExistenceFile.getName} with" +
          s" the correct contents."),
      Array(testExistenceFile.getName, TEST_EXISTENCE_FILE_CONTENTS))
  }

  test("Use a very long application name.") {
    assume(testBackend.name == MINIKUBE_TEST_BACKEND)

    testAppConf.setJars(Seq(CONTAINER_LOCAL_HELPER_JAR_PATH))
      .set("spark.app.name", "long" * 40)
    runSparkPiAndVerifyCompletion(CONTAINER_LOCAL_MAIN_APP_RESOURCE)
  }

  private def runSparkPiAndVerifyCompletion(appResource: String = ""): Unit = {
    runSparkApplicationAndVerifyCompletion(
        appResource,
        SPARK_PI_MAIN_CLASS,
        Seq("Pi is roughly 3"),
        Array.empty[String])
  }

  private def runSparkApplicationAndVerifyCompletion(
      appResource: String,
      mainClass: String,
      expectedLogOnCompletion: Seq[String],
      appArgs: Array[String]): Unit = {
    val appArguments = TestAppArguments(
      mainAppResource = appResource,
      mainClass = mainClass,
      driverArgs = appArgs,
      hadoopConfDir = None)
    TestApp.run(testAppConf, appArguments)
    val driverPod = kubernetesTestComponents.kubernetesClient
      .pods()
      .withLabel("spark-app-locator", APP_LOCATOR_LABEL)
      .list()
      .getItems
      .get(0)
    Eventually.eventually(TIMEOUT, INTERVAL) {
      expectedLogOnCompletion.foreach { e =>
        assert(kubernetesTestComponents.kubernetesClient
          .pods()
          .withName(driverPod.getMetadata.getName)
          .getLog
          .contains(e), "The application did not complete.")
      }
    }
  }

  private def createShuffleServiceDaemonSet(): Unit = {
    val ds = kubernetesTestComponents.kubernetesClient.extensions().daemonSets()
      .createNew()
        .withNewMetadata()
        .withName("shuffle")
      .endMetadata()
      .withNewSpec()
        .withNewTemplate()
          .withNewMetadata()
            .withLabels(Map("app" -> "spark-shuffle-service").asJava)
          .endMetadata()
          .withNewSpec()
            .addNewVolume()
              .withName("shuffle-dir")
              .withNewHostPath()
                .withPath("/tmp")
              .endHostPath()
            .endVolume()
            .addNewContainer()
              .withName("shuffle")
              .withImage("spark-shuffle:latest")
              .withImagePullPolicy("IfNotPresent")
              .addNewVolumeMount()
                .withName("shuffle-dir")
                .withMountPath("/tmp")
              .endVolumeMount()
            .endContainer()
          .endSpec()
        .endTemplate()
      .endSpec()
      .done()

    // wait for daemonset to become available.
    Eventually.eventually(TIMEOUT, INTERVAL) {
      val pods = kubernetesTestComponents.kubernetesClient.pods()
        .withLabel("app", "spark-shuffle-service").list().getItems

      if (pods.size() == 0 || !Readiness.isReady(pods.get(0))) {
        throw ShuffleNotReadyException
      }
    }
  }
}

private[spark] object KubernetesSuite {
  val EXAMPLES_JAR_FILE = Paths.get("target", "integration-tests-spark-jobs")
    .toFile
    .listFiles()(0)

  val HELPER_JAR_FILE = Paths.get("target", "integration-tests-spark-jobs-helpers")
    .toFile
    .listFiles()(0)
  val SUBMITTER_LOCAL_MAIN_APP_RESOURCE = s"file://${EXAMPLES_JAR_FILE.getAbsolutePath}"
  val CONTAINER_LOCAL_MAIN_APP_RESOURCE = s"local:///opt/spark/examples/" +
    s"integration-tests-jars/${EXAMPLES_JAR_FILE.getName}"
  val CONTAINER_LOCAL_HELPER_JAR_PATH = s"local:///opt/spark/examples/" +
    s"integration-tests-jars/${HELPER_JAR_FILE.getName}"
  val TIMEOUT = PatienceConfiguration.Timeout(Span(2, Minutes))
  val INTERVAL = PatienceConfiguration.Interval(Span(2, Seconds))
  val SPARK_PI_MAIN_CLASS = "org.apache.spark.deploy.k8s" +
    ".integrationtest.jobs.SparkPiWithInfiniteWait"
  val PYSPARK_PI_MAIN_CLASS = "org.apache.spark.deploy.PythonRunner"
  val SPARK_R_MAIN_CLASS = "org.apache.spark.deploy.RRunner"
  val PYSPARK_PI_CONTAINER_LOCAL_FILE_LOCATION =
    "local:///opt/spark/examples/src/main/python/pi.py"
  val PYSPARK_SORT_CONTAINER_LOCAL_FILE_LOCATION =
    "local:///opt/spark/examples/src/main/python/sort.py"
  val SPARK_R_DATAFRAME_SUBMITTER_FILE_LOCATION =
    "local:///opt/spark/examples/src/main/r/dataframe.R"
  val SPARK_R_DATAFRAME_CONTAINER_LOCAL_FILE_LOCATION =
    "src/test/R/dataframe.R"
  val PYSPARK_PI_SUBMITTER_LOCAL_FILE_LOCATION = "src/test/python/pi.py"
  val FILE_EXISTENCE_MAIN_CLASS = "org.apache.spark.deploy.k8s" +
    ".integrationtest.jobs.FileExistenceTest"
  val GROUP_BY_MAIN_CLASS = "org.apache.spark.deploy.k8s" +
    ".integrationtest.jobs.GroupByTest"
  val JAVA_OPTIONS_MAIN_CLASS = "org.apache.spark.deploy.k8s" +
    ".integrationtest.jobs.JavaOptionsTest"
  val TEST_EXISTENCE_FILE_CONTENTS = "contents"

  case object ShuffleNotReadyException extends Exception
}
