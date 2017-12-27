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
import java.nio.file.Paths
import java.util.UUID
import java.util.regex.Pattern

import com.google.common.io.PatternFilenameFilter
import io.fabric8.kubernetes.api.model.Pod
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FunSuite}
import org.scalatest.concurrent.{Eventually, PatienceConfiguration}
import org.scalatest.time.{Minutes, Seconds, Span}

import org.apache.spark.deploy.k8s.integrationtest.backend.IntegrationTestBackendFactory
import org.apache.spark.deploy.k8s.integrationtest.constants.SPARK_DISTRO_PATH

private[spark] class KubernetesSuite extends FunSuite with BeforeAndAfterAll with BeforeAndAfter {

  import KubernetesSuite._
  private val testBackend = IntegrationTestBackendFactory.getTestBackend()
  private val APP_LOCATOR_LABEL = UUID.randomUUID().toString.replaceAll("-", "")
  private var kubernetesTestComponents: KubernetesTestComponents = _
  private var sparkAppConf: SparkAppConf = _

  override def beforeAll(): Unit = {
    testBackend.initialize()
    kubernetesTestComponents = new KubernetesTestComponents(testBackend.getKubernetesClient)
  }

  override def afterAll(): Unit = {
    testBackend.cleanUp()
  }

  before {
    sparkAppConf = kubernetesTestComponents.newSparkAppConf()
      .set("spark.kubernetes.driver.label.spark-app-locator", APP_LOCATOR_LABEL)
    kubernetesTestComponents.createNamespace()
  }

  after {
    kubernetesTestComponents.deleteNamespace()
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
    sparkAppConf.set("spark.master", s"k8s://${url.getHost}:${url.getPort}")
    runSparkPiAndVerifyCompletion()
  }

  test("Run SparkPi with custom driver pod name, labels, annotations, and environment variables.") {
    sparkAppConf
      .set("spark.kubernetes.driver.pod.name", "spark-integration-spark-pi")
      .set("spark.kubernetes.driver.label.label1", "label1-value")
      .set("spark.kubernetes.driver.label.label2", "label2-value")
      .set("spark.kubernetes.driver.annotation.annotation1", "annotation1-value")
      .set("spark.kubernetes.driver.annotation.annotation2", "annotation2-value")
      .set("spark.kubernetes.driverEnv.ENV1", "VALUE1")
      .set("spark.kubernetes.driverEnv.ENV2", "VALUE2")
    runSparkPiAndVerifyCompletion(driverPodChecker = (driverPod: Pod) => {
      doBasicDriverPodCheck(driverPod)
      assert(driverPod.getMetadata.getName === "spark-integration-spark-pi")

      assert(driverPod.getMetadata.getLabels.get("label1") === "label1-value")
      assert(driverPod.getMetadata.getLabels.get("label2") === "label2-value")
      assert(driverPod.getMetadata.getAnnotations.get("annotation1") === "annotation1-value")
      assert(driverPod.getMetadata.getAnnotations.get("annotation2") === "annotation2-value")

      val driverContainer = driverPod.getSpec.getContainers.get(0)
      assert(driverContainer.getEnv.size() == 2)
      assert(driverContainer.getEnv.get(0).getName === "ENV1")
      assert(driverContainer.getEnv.get(0).getValue === "VALUE1")
      assert(driverContainer.getEnv.get(1).getName === "ENV2")
      assert(driverContainer.getEnv.get(1).getValue === "VALUE2")
    })
  }

  private def runSparkPiAndVerifyCompletion(
      appResource: String = CONTAINER_LOCAL_SPARK_DISTRO_EXAMPLES_JAR,
      driverPodChecker: Pod => Unit = doBasicDriverPodCheck): Unit = {
    runSparkApplicationAndVerifyCompletion(
      appResource,
      SPARK_PI_MAIN_CLASS,
      Seq("Pi is roughly 3"),
      Array.empty[String],
      driverPodChecker)
  }

  private def runSparkApplicationAndVerifyCompletion(
      appResource: String,
      mainClass: String,
      expectedLogOnCompletion: Seq[String],
      appArgs: Array[String],
      driverPodChecker: Pod => Unit): Unit = {
    val appArguments = SparkAppArguments(
      mainAppResource = appResource,
      mainClass = mainClass)
    SparkAppLauncher.launch(appArguments, sparkAppConf, TIMEOUT.value.toSeconds.toInt)
    val driverPod = kubernetesTestComponents.kubernetesClient
      .pods()
      .withLabel("spark-app-locator", APP_LOCATOR_LABEL)
      .list()
      .getItems
      .get(0)
    driverPodChecker(driverPod)
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
    assert(driverPod.getMetadata.getLabels.get("spark-role") === "driver")
  }
}

private[spark] object KubernetesSuite {

  val TIMEOUT = PatienceConfiguration.Timeout(Span(2, Minutes))
  val INTERVAL = PatienceConfiguration.Interval(Span(2, Seconds))
  val SPARK_DISTRO_EXAMPLES_JAR_FILE: File = Paths.get(SPARK_DISTRO_PATH.toFile.getAbsolutePath,
    "examples", "jars")
    .toFile
    .listFiles(new PatternFilenameFilter(Pattern.compile("^spark-examples_.*\\.jar$")))(0)
  val CONTAINER_LOCAL_SPARK_DISTRO_EXAMPLES_JAR: String = s"local:///opt/spark/examples/jars/" +
    s"${SPARK_DISTRO_EXAMPLES_JAR_FILE.getName}"
  val SPARK_PI_MAIN_CLASS: String = "org.apache.spark.examples.SparkPi"

  case object ShuffleNotReadyException extends Exception
}
