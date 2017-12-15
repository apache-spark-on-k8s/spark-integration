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

import java.util.UUID

import io.fabric8.kubernetes.client.DefaultKubernetesClient
import org.scalatest.concurrent.Eventually
import scala.collection.JavaConverters._

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

  def newTestJobConf(): TestAppConf = {
    new TestAppConf()
      .set("spark.master", s"k8s://${kubernetesClient.getMasterUrl}")
      .set("spark.kubernetes.namespace", namespace)
      .set("spark.kubernetes.driver.docker.image",
        System.getProperty("spark.docker.test.driverImage", "spark-driver:latest"))
      .set("spark.kubernetes.executor.docker.image",
        System.getProperty("spark.docker.test.executorImage", "spark-executor:latest"))
      .setJars(Seq(KubernetesSuite.HELPER_JAR_FILE.getAbsolutePath))
      .set("spark.executor.memory", "500m")
      .set("spark.executor.cores", "1")
      .set("spark.executors.instances", "1")
      .set("spark.app.name", "spark-test-app")
      .set("spark.ui.enabled", "true")
      .set("spark.testing", "false")
      .set("spark.kubernetes.submission.waitAppCompletion", "false")
  }
}
