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

import java.io.File
import java.nio.file.{Path, Paths}
import java.util.UUID
import java.util.regex.Pattern
import java.sql.DriverManager
import org.apache.hive.jdbc.HiveDriver

import scala.collection.JavaConverters._
import com.google.common.io.PatternFilenameFilter
import io.fabric8.kubernetes.api.model.{Container, Pod}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FunSuite}
import org.scalatest.concurrent.{Eventually, PatienceConfiguration}
import org.scalatest.time.{Minutes, Seconds, Span}

import org.apache.spark.deploy.k8s.integrationtest.backend.{IntegrationTestBackend, IntegrationTestBackendFactory}
import org.apache.spark.deploy.k8s.integrationtest.config._

private[spark] class KubernetesSuite extends FunSuite with BeforeAndAfterAll with BeforeAndAfter {

  import KubernetesSuite._

  private var testBackend: IntegrationTestBackend = _
  private var sparkHomeDir: Path = _
  private var kubernetesTestComponents: KubernetesTestComponents = _
  private var sparkAppConf: SparkAppConf = _
  private var image: String = _
  private var containerLocalSparkDistroExamplesJar: String = _
  private var appLocator: String = _
  private var driverPodName: String = _

  override def beforeAll(): Unit = {
    // The scalatest-maven-plugin gives system properties that are referenced but not set null
    // values. We need to remove the null-value properties before initializing the test backend.
    val nullValueProperties = System.getProperties.asScala
      .filter(entry => entry._2.equals("null"))
      .map(entry => entry._1.toString)
    nullValueProperties.foreach { key =>
      System.clearProperty(key)
    }

    val sparkDirProp = System.getProperty("spark.kubernetes.test.unpackSparkDir")
    require(sparkDirProp != null, "Spark home directory must be provided in system properties.")
    sparkHomeDir = Paths.get(sparkDirProp)
    require(sparkHomeDir.toFile.isDirectory,
      s"No directory found for spark home specified at $sparkHomeDir.")
    val imageTag = getTestImageTag
    val imageRepo = getTestImageRepo
    image = s"$imageRepo/spark:$imageTag"

    val sparkDistroExamplesJarFile: File = sparkHomeDir.resolve(Paths.get("examples", "jars"))
      .toFile
      .listFiles(new PatternFilenameFilter(Pattern.compile("^spark-examples_.*\\.jar$")))(0)
    containerLocalSparkDistroExamplesJar = s"local:///opt/spark/examples/jars/" +
      s"${sparkDistroExamplesJarFile.getName}"
    testBackend = IntegrationTestBackendFactory.getTestBackend
    testBackend.initialize()
    kubernetesTestComponents = new KubernetesTestComponents(testBackend.getKubernetesClient)
  }

  override def afterAll(): Unit = {
    testBackend.cleanUp()
  }

  before {
    appLocator = UUID.randomUUID().toString.replaceAll("-", "")
    driverPodName = "spark-test-app-" + UUID.randomUUID().toString.replaceAll("-", "")
    sparkAppConf = kubernetesTestComponents.newSparkAppConf()
      .set("spark.kubernetes.container.image", image)
      .set("spark.kubernetes.driver.pod.name", driverPodName)
      .set("spark.kubernetes.driver.label.spark-app-locator", appLocator)
      .set("spark.kubernetes.executor.label.spark-app-locator", appLocator)
    if (!kubernetesTestComponents.hasUserSpecifiedNamespace) {
      kubernetesTestComponents.createNamespace()
    }
  }

  after {
    if (!kubernetesTestComponents.hasUserSpecifiedNamespace) {
      kubernetesTestComponents.deleteNamespace()
    }
    deleteDriverPod()
  }

  test("Run SparkPi with no resources") {
    runSparkPiAndVerifyCompletion()
  }

  test("Run SparkPi with a very long application name.") {
    sparkAppConf.set("spark.app.name", "long" * 40)
    runSparkPiAndVerifyCompletion()
  }

  test("Run SparkPi with a master URL without a scheme.") {
    val url = kubernetesTestComponents.kubernetesClient.getMasterUrl
    val k8sMasterUrl = if (url.getPort < 0) {
      s"k8s://${url.getHost}"
    } else {
      s"k8s://${url.getHost}:${url.getPort}"
    }
    sparkAppConf.set("spark.master", k8sMasterUrl)
    runSparkPiAndVerifyCompletion()
  }

  test("Run SparkPi with an argument.") {
    runSparkPiAndVerifyCompletion(appArgs = Array("5"))
  }

  test("Run Spark Thrift Server") {
    runThriftServerAndVerifyQuery()
  }

  test("Run SparkPi with custom labels, annotations, and environment variables.") {
    sparkAppConf
      .set("spark.kubernetes.driver.label.label1", "label1-value")
      .set("spark.kubernetes.driver.label.label2", "label2-value")
      .set("spark.kubernetes.driver.annotation.annotation1", "annotation1-value")
      .set("spark.kubernetes.driver.annotation.annotation2", "annotation2-value")
      .set("spark.kubernetes.driverEnv.ENV1", "VALUE1")
      .set("spark.kubernetes.driverEnv.ENV2", "VALUE2")
      .set("spark.kubernetes.executor.label.label1", "label1-value")
      .set("spark.kubernetes.executor.label.label2", "label2-value")
      .set("spark.kubernetes.executor.annotation.annotation1", "annotation1-value")
      .set("spark.kubernetes.executor.annotation.annotation2", "annotation2-value")
      .set("spark.executorEnv.ENV1", "VALUE1")
      .set("spark.executorEnv.ENV2", "VALUE2")

    runSparkPiAndVerifyCompletion(
      driverPodChecker = (driverPod: Pod) => {
        doBasicDriverPodCheck(driverPod)
        checkCustomSettings(driverPod)
      },
      executorPodChecker = (executorPod: Pod) => {
        doBasicExecutorPodCheck(executorPod)
        checkCustomSettings(executorPod)
      })
  }

  test("Run SparkPi with a test secret mounted into the driver and executor pods") {
    val secretName = TEST_SECRET_NAME_PREFIX + UUID.randomUUID().toString.replaceAll("-", "")
    createTestSecret(secretName)

    sparkAppConf
      .set(s"spark.kubernetes.driver.secrets.$secretName", TEST_SECRET_MOUNT_PATH)
      .set(s"spark.kubernetes.executor.secrets.$secretName", TEST_SECRET_MOUNT_PATH)

    try {
      runSparkPiAndVerifyCompletion(
        driverPodChecker = (driverPod: Pod) => {
          doBasicDriverPodCheck(driverPod)
          checkTestSecret(secretName, driverPod)
        },
        executorPodChecker = (executorPod: Pod) => {
          doBasicExecutorPodCheck(executorPod)
          checkTestSecret(secretName, executorPod)
        })
    } finally {
      deleteTestSecret(secretName)
    }
  }

  test("Run PageRank using remote data file") {
    sparkAppConf
      .set("spark.kubernetes.mountDependencies.filesDownloadDir",
        CONTAINER_LOCAL_FILE_DOWNLOAD_PATH)
      .set("spark.files", REMOTE_PAGE_RANK_DATA_FILE)
    runSparkPageRankAndVerifyCompletion(
      appArgs = Array(CONTAINER_LOCAL_DOWNLOADED_PAGE_RANK_DATA_FILE))
  }

  test("Run PageRank using remote data file with test secret mounted into the driver and " +
    "executors") {
    val secretName = TEST_SECRET_NAME_PREFIX + UUID.randomUUID().toString.replaceAll("-", "")
    createTestSecret(secretName)

    sparkAppConf
      .set("spark.kubernetes.mountDependencies.filesDownloadDir",
        CONTAINER_LOCAL_FILE_DOWNLOAD_PATH)
      .set("spark.files", REMOTE_PAGE_RANK_DATA_FILE)
      .set(s"spark.kubernetes.driver.secrets.$secretName", TEST_SECRET_MOUNT_PATH)
      .set(s"spark.kubernetes.executor.secrets.$secretName", TEST_SECRET_MOUNT_PATH)

    try {
      runSparkPageRankAndVerifyCompletion(
        appArgs = Array(CONTAINER_LOCAL_DOWNLOADED_PAGE_RANK_DATA_FILE),
        driverPodChecker = (driverPod: Pod) => {
          doBasicDriverPodCheck(driverPod)
          checkTestSecret(secretName, driverPod, withInitContainer = true)
        },
        executorPodChecker = (executorPod: Pod) => {
          doBasicExecutorPodCheck(executorPod)
          checkTestSecret(secretName, executorPod, withInitContainer = true)
        })
    } finally {
      deleteTestSecret(secretName)
    }
  }

  private def runSparkPiAndVerifyCompletion(
      appResource: String = containerLocalSparkDistroExamplesJar,
      driverPodChecker: Pod => Unit = doBasicDriverPodCheck,
      executorPodChecker: Pod => Unit = doBasicExecutorPodCheck,
      appArgs: Array[String] = Array.empty[String],
      appLocator: String = appLocator): Unit = {
    runSparkApplicationAndVerifyCompletion(
      appResource,
      SPARK_PI_MAIN_CLASS,
      Seq("Pi is roughly 3"),
      appArgs,
      driverPodChecker,
      executorPodChecker,
      appLocator)
  }

  private def runSparkPageRankAndVerifyCompletion(
      appResource: String = containerLocalSparkDistroExamplesJar,
      driverPodChecker: Pod => Unit = doBasicDriverPodCheck,
      executorPodChecker: Pod => Unit = doBasicExecutorPodCheck,
      appArgs: Array[String],
      appLocator: String = appLocator): Unit = {
    runSparkApplicationAndVerifyCompletion(
      appResource,
      SPARK_PAGE_RANK_MAIN_CLASS,
      Seq("1 has rank", "2 has rank", "3 has rank", "4 has rank"),
      appArgs,
      driverPodChecker,
      executorPodChecker,
      appLocator)
  }

  private def runThriftServerAndVerifyQuery(
                                             driverPodChecker: Pod => Unit = doBasicDriverPodCheck,
                                             appArgs: Array[String] = Array.empty[String]): Unit = {
    val appArguments = SparkAppArguments(
      mainAppResource = "",
      mainClass = "org.apache.spark.sql.hive.thriftserver.HiveThriftServer2",
      appArgs = appArgs)
    SparkAppLauncher.launch(appArguments, sparkAppConf, TIMEOUT.value.toSeconds.toInt, sparkHomeDir)
    val driverPod = kubernetesTestComponents.kubernetesClient
      .pods
      .withLabel("spark-app-locator", APP_LOCATOR_LABEL)
      .withLabel("spark-role", "driver")
      .list()
      .getItems
      .get(0)
    driverPodChecker(driverPod)
    val driverPodResource = kubernetesTestComponents.kubernetesClient
      .pods
      .withName(driverPod.getMetadata.getName)
    
    Eventually.eventually(TIMEOUT, INTERVAL) {
      val localPort = driverPodResource.portForward(10000).getLocalPort
      val jdbcUri = s"jdbc:hive2://localhost:$localPort/"
      val connection = DriverManager.getConnection(jdbcUri, "user", "pass")
      val statement = connection.createStatement()
      try {
        val resultSet = statement.executeQuery("select 42")
        resultSet.next()
        assert(resultSet.getInt(1) == 42)
      } finally {
        statement.close()
        connection.close()
      }
    }
  }

  private def runSparkApplicationAndVerifyCompletion(
      appResource: String,
      mainClass: String,
      expectedLogOnCompletion: Seq[String],
      appArgs: Array[String],
      driverPodChecker: Pod => Unit,
      executorPodChecker: Pod => Unit,
      appLocator: String): Unit = {
    val appArguments = SparkAppArguments(
      mainAppResource = appResource,
      mainClass = mainClass,
      appArgs = appArgs)
    SparkAppLauncher.launch(appArguments, sparkAppConf, TIMEOUT.value.toSeconds.toInt, sparkHomeDir)

    val driverPod = kubernetesTestComponents.kubernetesClient
      .pods()
      .withLabel("spark-app-locator", appLocator)
      .withLabel("spark-role", "driver")
      .list()
      .getItems
      .get(0)
    driverPodChecker(driverPod)

    val executorPods = kubernetesTestComponents.kubernetesClient
      .pods()
      .withLabel("spark-app-locator", appLocator)
      .withLabel("spark-role", "executor")
      .list()
      .getItems
    executorPods.asScala.foreach { pod =>
      executorPodChecker(pod)
    }

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

  private def doBasicDriverPodCheck(driverPod: Pod): Unit = {
    assert(driverPod.getMetadata.getName === driverPodName)
    assert(driverPod.getSpec.getContainers.get(0).getImage === image)
    assert(driverPod.getSpec.getContainers.get(0).getName === "spark-kubernetes-driver")
  }

  private def doBasicExecutorPodCheck(executorPod: Pod): Unit = {
    assert(executorPod.getSpec.getContainers.get(0).getImage === image)
    assert(executorPod.getSpec.getContainers.get(0).getName === "executor")
  }

  private def checkCustomSettings(pod: Pod): Unit = {
    assert(pod.getMetadata.getLabels.get("label1") === "label1-value")
    assert(pod.getMetadata.getLabels.get("label2") === "label2-value")
    assert(pod.getMetadata.getAnnotations.get("annotation1") === "annotation1-value")
    assert(pod.getMetadata.getAnnotations.get("annotation2") === "annotation2-value")

    val container = pod.getSpec.getContainers.get(0)
    val envVars = container
      .getEnv
      .asScala
      .map { env =>
        (env.getName, env.getValue)
      }
      .toMap
    assert(envVars("ENV1") === "VALUE1")
    assert(envVars("ENV2") === "VALUE2")
  }

  private def deleteDriverPod(): Unit = {
    kubernetesTestComponents.kubernetesClient.pods().withName(driverPodName).delete()
    Eventually.eventually(TIMEOUT, INTERVAL) {
      assert(kubernetesTestComponents.kubernetesClient
        .pods()
        .withName(driverPodName)
        .get() == null)
    }
  }

  private def createTestSecret(secretName: String): Unit = {
    kubernetesTestComponents.kubernetesClient.secrets
      .createNew()
      .editOrNewMetadata()
        .withName(secretName)
        .endMetadata()
      .addToStringData(TEST_SECRET_KEY, TEST_SECRET_VALUE)
      .done()
  }

  private def checkTestSecret(
      secretName: String,
      pod: Pod,
      withInitContainer: Boolean = false): Unit = {
    val testSecretVolume = pod.getSpec.getVolumes.asScala.filter { volume =>
      volume.getName == s"$secretName-volume"
    }
    assert(testSecretVolume.size === 1)
    assert(testSecretVolume.head.getSecret.getSecretName === secretName)

    checkTestSecretInContainer(secretName, pod.getSpec.getContainers.get(0))

    if (withInitContainer) {
      checkTestSecretInContainer(secretName, pod.getSpec.getInitContainers.get(0))
    }
  }

  private def checkTestSecretInContainer(secretName: String, container: Container): Unit = {
    val testSecret = container.getVolumeMounts.asScala.filter { mount =>
      mount.getName == s"$secretName-volume"
    }
    assert(testSecret.size === 1)
    assert(testSecret.head.getMountPath === TEST_SECRET_MOUNT_PATH)
  }

  private def deleteTestSecret(secretName: String): Unit = {
    kubernetesTestComponents.kubernetesClient.secrets
      .withName(secretName)
      .delete()

    Eventually.eventually(TIMEOUT, INTERVAL) {
      assert(kubernetesTestComponents.kubernetesClient.secrets.withName(secretName).get() == null)
    }
  }
}

private[spark] object KubernetesSuite {

  val TIMEOUT = PatienceConfiguration.Timeout(Span(2, Minutes))
  val INTERVAL = PatienceConfiguration.Interval(Span(2, Seconds))
  val SPARK_PI_MAIN_CLASS: String = "org.apache.spark.examples.SparkPi"
  val SPARK_PAGE_RANK_MAIN_CLASS: String = "org.apache.spark.examples.SparkPageRank"

  val TEST_SECRET_NAME_PREFIX = "test-secret-"
  val TEST_SECRET_KEY = "test-key"
  val TEST_SECRET_VALUE = "test-data"
  val TEST_SECRET_MOUNT_PATH = "/etc/secrets"

  val CONTAINER_LOCAL_FILE_DOWNLOAD_PATH = "/var/spark-data/spark-files"

  val REMOTE_PAGE_RANK_DATA_FILE =
    "https://storage.googleapis.com/spark-k8s-integration-tests/files/pagerank_data.txt"
  val CONTAINER_LOCAL_DOWNLOADED_PAGE_RANK_DATA_FILE =
    s"$CONTAINER_LOCAL_FILE_DOWNLOAD_PATH/pagerank_data.txt"

  case object ShuffleNotReadyException extends Exception
}
