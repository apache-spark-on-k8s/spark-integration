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
package org.apache.spark.deploy.k8s.integrationtest.backend.minikube

import io.fabric8.kubernetes.client.DefaultKubernetesClient

import org.apache.spark.deploy.k8s.integrationtest.backend.IntegrationTestBackend
import org.apache.spark.deploy.k8s.integrationtest.config._
import org.apache.spark.deploy.k8s.integrationtest.docker.KubernetesSuiteDockerManager

private[spark] object MinikubeTestBackend extends IntegrationTestBackend {
  private var defaultClient: DefaultKubernetesClient = _
  private val userProvidedDockerImageTag = Option(
    System.getProperty(KUBERNETES_TEST_DOCKER_TAG_SYSTEM_PROPERTY))
  private val dockerManager = new KubernetesSuiteDockerManager(
    Minikube.getDockerEnv, userProvidedDockerImageTag)

  override def initialize(): Unit = {
    val minikubeStatus = Minikube.getMinikubeStatus
    require(minikubeStatus == MinikubeStatus.RUNNING,
      s"Minikube must be running before integration tests can execute. Current status" +
        s" is: $minikubeStatus")
    dockerManager.buildSparkDockerImages()
    defaultClient = Minikube.getKubernetesClient
  }

  override def cleanUp(): Unit = {
    super.cleanUp()
    dockerManager.deleteImages()
  }

  override def getKubernetesClient(): DefaultKubernetesClient = {
    defaultClient
  }

  override def dockerImageTag(): String = dockerManager.dockerImageTag()
}
