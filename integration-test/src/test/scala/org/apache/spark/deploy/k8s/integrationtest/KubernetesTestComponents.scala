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

import java.nio.file.Paths
import java.util.UUID

import scala.collection.mutable
import scala.collection.JavaConverters._

import io.fabric8.kubernetes.client.DefaultKubernetesClient
import org.scalatest.concurrent.Eventually

import org.apache.spark.deploy.k8s.integrationtest.constants.SPARK_DISTRO_PATH

private[spark] class KubernetesTestComponents(defaultClient: DefaultKubernetesClient) {

  val namespace = UUID.randomUUID().toString.replaceAll("-", "")
  val kubernetesClient = defaultClient.inNamespace(namespace)
  val clientConfig = kubernetesClient.getConfiguration

  def createNamespace(): Unit = {
    defaultClient.namespaces.createNew()
      .withNewMetadata()
      .withName(namespace)
      .endMetadata()
      .done()
  }

  def deleteNamespace(): Unit = {
    defaultClient.namespaces.withName(namespace).delete()
    Eventually.eventually(KubernetesSuite.TIMEOUT, KubernetesSuite.INTERVAL) {
      val namespaceList = defaultClient
        .namespaces()
        .list()
        .getItems()
        .asScala
      require(!namespaceList.exists(_.getMetadata.getName == namespace))
    }
  }

  def newSparkAppConf(): SparkAppConf = {
    new SparkAppConf()
      .set("spark.master", s"k8s://${kubernetesClient.getMasterUrl}")
      .set("spark.kubernetes.namespace", namespace)
      .set("spark.kubernetes.driver.container.image",
        System.getProperty("spark.docker.test.driverImage", "spark-driver:latest"))
      .set("spark.kubernetes.executor.container.image",
        System.getProperty("spark.docker.test.executorImage", "spark-executor:latest"))
      .set("spark.executor.memory", "500m")
      .set("spark.executor.cores", "1")
      .set("spark.executors.instances", "1")
      .set("spark.app.name", "spark-test-app")
      .set("spark.ui.enabled", "true")
      .set("spark.testing", "false")
      .set("spark.kubernetes.submission.waitAppCompletion", "false")
  }
}

private[spark] class SparkAppConf {

  private val map = mutable.Map[String, String]()

  def set(key:String, value: String): SparkAppConf = {
    map.put(key, value)
    this
  }

  def get(key: String): String = map.getOrElse(key, "")

  def setJars(jars: Seq[String]) = set("spark.jars", jars.mkString(","))

  override def toString: String = map.toString

  def toStringArray: Iterable[String] = map.toList.flatMap(t => List("--conf", s"${t._1}=${t._2}"))
}

private[spark] case class SparkAppArguments(
    mainAppResource: String,
    mainClass: String)

private[spark] object SparkAppLauncher extends Logging {

  private val SPARK_SUBMIT_EXECUTABLE_DEST = Paths.get(SPARK_DISTRO_PATH.toFile.getAbsolutePath,
      "bin", "spark-submit").toFile

  def launch(appArguments: SparkAppArguments, appConf: SparkAppConf, timeoutSecs: Int): Unit = {
    logInfo(s"Launching a spark app with arguments $appArguments and conf $appConf")
    val commandLine = Array(SPARK_SUBMIT_EXECUTABLE_DEST.getAbsolutePath,
        "--deploy-mode", "cluster",
        "--class", appArguments.mainClass,
        "--master", appConf.get("spark.master")
      ) ++ appConf.toStringArray :+ appArguments.mainAppResource
    logInfo(s"Launching a spark app with command line: ${commandLine.mkString(" ")}")
    ProcessUtils.executeProcess(commandLine, timeoutSecs)
  }
}
