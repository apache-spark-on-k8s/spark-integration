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
import java.util.regex.Pattern

import com.google.common.base.Charsets
import com.google.common.io.{Files, PatternFilenameFilter}
import io.fabric8.kubernetes.client.internal.readiness.Readiness
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FunSuite}
import org.scalatest.concurrent.{Eventually, PatienceConfiguration}
import org.scalatest.time.{Minutes, Seconds, Span}
import scala.collection.JavaConverters._

import org.apache.spark.deploy.k8s.integrationtest.backend.IntegrationTestBackendFactory
import org.apache.spark.deploy.k8s.integrationtest.constants.MINIKUBE_TEST_BACKEND
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
      .set("spark.kubernetes.initcontainer.docker.image", "spark-init:latest")
      .set("spark.kubernetes.driver.docker.image", "spark-driver:latest")
      .set("spark.kubernetes.driver.label.spark-app-locator", APP_LOCATOR_LABEL)
    kubernetesTestComponents.createNamespace()
  }

  after {
    kubernetesTestComponents.deleteNamespace()
  }

  test("Run SparkPi with no resources") {
    assume(testBackend.name == MINIKUBE_TEST_BACKEND)

    runSparkPiAndVerifyCompletion()
  }

  test("Run SparkPi with a very long application name.") {
    assume(testBackend.name == MINIKUBE_TEST_BACKEND)

    sparkAppConf.set("spark.app.name", "long" * 40)
    runSparkPiAndVerifyCompletion()
  }

  private def runSparkPiAndVerifyCompletion(
      appResource: String = CONTAINER_LOCAL_SPARK_DISTRO_EXAMPLES_JAR): Unit = {
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
}

private[spark] object KubernetesSuite {

  val TIMEOUT = PatienceConfiguration.Timeout(Span(2, Minutes))
  val INTERVAL = PatienceConfiguration.Interval(Span(2, Seconds))
  val SPARK_DISTRO_EXAMPLES_JAR_FILE: File = Paths.get(SPARK_DISTRO_PATH.toFile.getAbsolutePath,
    "examples", "jars")
    .toFile
    .listFiles(new PatternFilenameFilter(Pattern.compile("^spark-examples_.*\\.jar$")))(0)
  val CONTAINER_LOCAL_SPARK_DISTRO_EXAMPLES_JAR: String = s"local:///opt/spark/examples/" +
    s"${SPARK_DISTRO_EXAMPLES_JAR_FILE.getName}"
  val SPARK_PI_MAIN_CLASS: String = "org.apache.spark.examples.SparkPi"

  case object ShuffleNotReadyException extends Exception
}
