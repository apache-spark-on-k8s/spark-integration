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

import java.net.{URI, URL}
import java.nio.file.Paths
import java.util.UUID

import io.fabric8.kubernetes.api.model.{Endpoints, Pod, Service}
import org.apache.http.client.utils.URIBuilder

private[spark] object SparkExamplesFileServerRunner {

  private val fileServerImage = System.getProperty(
      "spark.docker.test.fileServerImage", "spark-examples-file-server:latest")
  private val fileServerExampleJarsDir = Paths.get("docker-file-server", "jars")
  require(
    fileServerExampleJarsDir
      .toFile
      .listFiles()
      .exists(file => file.getName.startsWith("spark-examples")),
      s"No spark-examples jar found in $fileServerExampleJarsDir.")
  require(
    fileServerExampleJarsDir
      .toFile
      .listFiles()
      .count(file => file.getName.startsWith("spark-examples")) == 1,
      s"Multiple spark-examples jars found in $fileServerExampleJarsDir.")
  private val fileServerExampleJar = Paths.get("docker-file-server", "jars")
    .toFile
    .listFiles()
    .filter(file => file.getName.startsWith("spark-examples"))(0)
    .getName
  private val fileServerPodLocatorLabelKey = "fileServerLocator"
  private val fileServerPodLocatorLabelValue = UUID.randomUUID().toString.replaceAll("-", "")
  private val fileServerName = "spark-examples-file-server"

  def launchServerAndGetUriForExamplesJar(
    kubernetesTestComponents: KubernetesTestComponents): URI = {
    val podReadinessWatcher = new SparkReadinessWatcher[Pod]
    Utils.tryWithResource(
      kubernetesTestComponents
        .kubernetesClient
        .pods()
        .withName(fileServerName)
        .watch(podReadinessWatcher)) { _ =>
          kubernetesTestComponents.kubernetesClient.pods().createNew()
            .withNewMetadata()
              .withName(fileServerName)
              .addToLabels(fileServerPodLocatorLabelKey, fileServerPodLocatorLabelValue)
              .endMetadata()
            .withNewSpec()
              .addNewContainer()
                .withName("main")
                .withImage(fileServerImage)
                .withImagePullPolicy("Never")
                .withNewReadinessProbe()
                  .withNewHttpGet()
                    .withNewPort(80)
                    .withPath("/ping")
                    .endHttpGet()
                  .endReadinessProbe()
                .endContainer()
              .endSpec()
            .done()
          podReadinessWatcher.waitUntilReady()
    }
    val endpointsReadinessWatcher = new SparkReadinessWatcher[Endpoints]
    Utils.tryWithResource(
      kubernetesTestComponents
        .kubernetesClient
        .endpoints()
        .withName(fileServerName)
        .watch(endpointsReadinessWatcher)) { _ =>
      kubernetesTestComponents.kubernetesClient.services().createNew()
        .withNewMetadata()
          .withName(fileServerName)
          .endMetadata()
        .withNewSpec()
          .addToSelector(fileServerPodLocatorLabelKey, fileServerPodLocatorLabelValue)
          .addNewPort()
            .withName("file-server-port")
            .withNewTargetPort(80)
            .withPort(80)
            .endPort()
          .withType("NodePort")
          .endSpec()
        .done()
      endpointsReadinessWatcher.waitUntilReady()
    }
    val resolvedNodePort = kubernetesTestComponents
      .kubernetesClient
      .services()
      .withName(fileServerName)
      .get()
      .getSpec
      .getPorts
      .get(0)
      .getNodePort
    val masterHostname = URI.create(kubernetesTestComponents.clientConfig.getMasterUrl).getHost
    new URIBuilder()
      .setHost(masterHostname)
      .setPort(resolvedNodePort)
      .setScheme("http")
      .setPath(s"/$fileServerExampleJar")
      .build()
  }
}
