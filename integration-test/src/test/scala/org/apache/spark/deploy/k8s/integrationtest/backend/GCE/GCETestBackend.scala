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
package org.apache.spark.deploy.k8s.integrationtest.backend.GCE

import io.fabric8.kubernetes.client.{ConfigBuilder, DefaultKubernetesClient}

import org.apache.spark.deploy.k8s.integrationtest.backend.IntegrationTestBackend
import org.apache.spark.deploy.k8s.integrationtest.constants.GCE_TEST_BACKEND

private[spark] class GCETestBackend(val master: String) extends IntegrationTestBackend {
  private var defaultClient: DefaultKubernetesClient = _

  override def initialize(): Unit = {
    var k8ConfBuilder = new ConfigBuilder()
      .withApiVersion("v1")
      .withMasterUrl(master.replaceFirst("k8s://", ""))
    defaultClient = new DefaultKubernetesClient(k8ConfBuilder.build)
  }

  override def getKubernetesClient(): DefaultKubernetesClient = {
    defaultClient
  }

  override def name(): String = GCE_TEST_BACKEND
}
