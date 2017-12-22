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
package org.apache.spark.deploy.k8s.integrationtest.docker

import java.net.URI
import java.nio.file.Paths

import com.spotify.docker.client.{DefaultDockerClient, DockerCertificates, LoggingBuildHandler}
import org.apache.http.client.utils.URIBuilder
import org.scalatest.concurrent.{Eventually, PatienceConfiguration}
import org.scalatest.time.{Minutes, Seconds, Span}

import org.apache.spark.deploy.k8s.integrationtest.constants.SPARK_DISTRO_PATH
import org.apache.spark.deploy.k8s.integrationtest.Logging

private[spark] class SparkDockerImageBuilder
  (private val dockerEnv: Map[String, String]) extends Logging{

  private val DOCKER_BUILD_PATH = SPARK_DISTRO_PATH
  // Dockerfile paths must be relative to the build path.
  private val DOCKERFILES_DIR = "kubernetes/dockerfiles/"
  private val BASE_DOCKER_FILE = DOCKERFILES_DIR + "spark-base/Dockerfile"
  private val DRIVER_DOCKER_FILE = DOCKERFILES_DIR + "driver/Dockerfile"
  private val EXECUTOR_DOCKER_FILE = DOCKERFILES_DIR + "executor/Dockerfile"
  private val TIMEOUT = PatienceConfiguration.Timeout(Span(2, Minutes))
  private val INTERVAL = PatienceConfiguration.Interval(Span(2, Seconds))
  private val dockerHost = dockerEnv.getOrElse("DOCKER_HOST",
      throw new IllegalStateException("DOCKER_HOST env not found."))

  private val originalDockerUri = URI.create(dockerHost)
  private val httpsDockerUri = new URIBuilder()
    .setHost(originalDockerUri.getHost)
    .setPort(originalDockerUri.getPort)
    .setScheme("https")
    .build()

  private val dockerCerts = dockerEnv.getOrElse("DOCKER_CERT_PATH",
      throw new IllegalStateException("DOCKER_CERT_PATH env not found."))

  private val dockerClient = new DefaultDockerClient.Builder()
    .uri(httpsDockerUri)
    .dockerCertificates(DockerCertificates.builder()
      .dockerCertPath(Paths.get(dockerCerts))
      .build()
      .get())
    .build()

  def buildSparkDockerImages(): Unit = {
    Eventually.eventually(TIMEOUT, INTERVAL) { dockerClient.ping() }
    buildImage("spark-base", BASE_DOCKER_FILE)
    buildImage("spark-driver", DRIVER_DOCKER_FILE)
    buildImage("spark-executor", EXECUTOR_DOCKER_FILE)
  }

  private def buildImage(name: String, dockerFile: String): Unit = {
    dockerClient.build(
      DOCKER_BUILD_PATH,
      name,
      dockerFile,
      new LoggingBuildHandler())
    logInfo(s"Built $name docker image")
  }
}
