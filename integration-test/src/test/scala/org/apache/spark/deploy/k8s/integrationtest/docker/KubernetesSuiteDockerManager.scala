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

import java.io.{File, PrintWriter}
import java.net.URI
import java.nio.file.Paths
import java.util.UUID

import com.google.common.base.Charsets
import com.google.common.io.Files
import com.spotify.docker.client.{DefaultDockerClient, DockerCertificates, LoggingBuildHandler}
import com.spotify.docker.client.DockerClient.{ListContainersParam, ListImagesParam, RemoveContainerParam}
import com.spotify.docker.client.messages.Container
import org.apache.http.client.utils.URIBuilder
import org.scalatest.concurrent.{Eventually, PatienceConfiguration}
import org.scalatest.time.{Minutes, Seconds, Span}
import scala.collection.JavaConverters._

import org.apache.spark.deploy.k8s.integrationtest.constants._
import org.apache.spark.deploy.k8s.integrationtest.KubernetesSuite
import org.apache.spark.deploy.k8s.integrationtest.Logging
import org.apache.spark.deploy.k8s.integrationtest.Utils.tryWithResource

private[spark] class KubernetesSuiteDockerManager(
    dockerEnv: Map[String, String], userProvidedDockerImageTag: Option[String]) extends Logging {

  private val DOCKER_BUILD_PATH = SPARK_DISTRO_PATH
  // Dockerfile paths must be relative to the build path.
  private val DOCKERFILES_DIR = "kubernetes/dockerfiles/"
  private val BASE_DOCKER_FILE = DOCKERFILES_DIR + "spark-base/Dockerfile"
  private val DRIVER_DOCKER_FILE = DOCKERFILES_DIR + "driver/Dockerfile"
  private val EXECUTOR_DOCKER_FILE = DOCKERFILES_DIR + "executor/Dockerfile"
  private val INIT_CONTAINER_DOCKER_FILE = DOCKERFILES_DIR + "init-container/Dockerfile"
  private val TIMEOUT = PatienceConfiguration.Timeout(Span(2, Minutes))
  private val INTERVAL = PatienceConfiguration.Interval(Span(2, Seconds))

  private val resolvedDockerImageTag =
    userProvidedDockerImageTag.getOrElse(UUID.randomUUID().toString.replaceAll("-", ""))
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
    .dockerCertificates(DockerCertificates
      .builder()
      .dockerCertPath(Paths.get(dockerCerts))
      .build().get())
    .build()

  def buildSparkDockerImages(): Unit = {
    if (userProvidedDockerImageTag.isEmpty) {
      Eventually.eventually(TIMEOUT, INTERVAL) {
        dockerClient.ping()
      }
      buildImage("spark-base", BASE_DOCKER_FILE)
      buildImage("spark-driver", DRIVER_DOCKER_FILE)
      buildImage("spark-executor", EXECUTOR_DOCKER_FILE)
      buildImage("spark-init", INIT_CONTAINER_DOCKER_FILE)
    }
  }

  def deleteImages(): Unit = {
    if (userProvidedDockerImageTag.isEmpty) {
      removeRunningContainers()
      deleteImage("spark-base")
      deleteImage("spark-driver")
      deleteImage("spark-executor")
      deleteImage("spark-init")
    }
  }

  def dockerImageTag(): String = resolvedDockerImageTag

  private def buildImage(name: String, dockerFile: String): Unit = {
    logInfo(s"Building Docker image - $name:$resolvedDockerImageTag")
    val dockerFileWithBaseTag = new File(DOCKER_BUILD_PATH.resolve(
      s"$dockerFile-$resolvedDockerImageTag").toAbsolutePath.toString)
    dockerFileWithBaseTag.deleteOnExit()
    try {
      val originalDockerFileText = Files.readLines(
        DOCKER_BUILD_PATH.resolve(dockerFile).toFile, Charsets.UTF_8).asScala
      val dockerFileTextWithProperBaseImage = originalDockerFileText.map(
        _.replace("FROM spark-base", s"FROM spark-base:$resolvedDockerImageTag"))
      tryWithResource(Files.newWriter(dockerFileWithBaseTag, Charsets.UTF_8)) { fileWriter =>
        tryWithResource(new PrintWriter(fileWriter)) { printWriter =>
          for (line <- dockerFileTextWithProperBaseImage) {
            // scalastyle:off println
            printWriter.println(line)
            // scalastyle:on println
          }
        }
      }
      dockerClient.build(
        DOCKER_BUILD_PATH,
        s"$name:$resolvedDockerImageTag",
        s"$dockerFile-$resolvedDockerImageTag",
        new LoggingBuildHandler())
    } finally {
      dockerFileWithBaseTag.delete()
    }
  }

  /**
    * Forces all containers running an image with the configured tag to halt and be removed.
    */
  private def removeRunningContainers(): Unit = {
    val imageIds = dockerClient.listImages(ListImagesParam.allImages())
      .asScala
      .filter(image => image.repoTags().asScala.exists(_.endsWith(s":$resolvedDockerImageTag")))
      .map(_.id())
      .toSet
    Eventually.eventually(KubernetesSuite.TIMEOUT, KubernetesSuite.INTERVAL) {
      val runningContainersWithImageTag = stopRunningContainers(imageIds)
      require(
        runningContainersWithImageTag.isEmpty,
        s"${runningContainersWithImageTag.size} containers found still running" +
          s" with the image tag $resolvedDockerImageTag")
    }
    dockerClient.listContainers(ListContainersParam.allContainers())
      .asScala
      .filter(container => imageIds.contains(container.imageId()))
      .foreach(container => dockerClient.removeContainer(
        container.id(), RemoveContainerParam.forceKill(true)))
    Eventually.eventually(KubernetesSuite.TIMEOUT, KubernetesSuite.INTERVAL) {
      val containersWithImageTag = dockerClient.listContainers(ListContainersParam.allContainers())
        .asScala
        .filter(container => imageIds.contains(container.imageId()))
      require(containersWithImageTag.isEmpty, s"${containersWithImageTag.size} containers still" +
        s" found with image tag $resolvedDockerImageTag.")
    }

  }

  private def stopRunningContainers(imageIds: Set[String]): Iterable[Container] = {
    val runningContainersWithImageTag = getRunningContainersWithImageIds(imageIds)
    if (runningContainersWithImageTag.nonEmpty) {
      logInfo(s"Found ${runningContainersWithImageTag.size} containers running with" +
        s" an image with the tag $resolvedDockerImageTag. Attempting to remove these containers," +
        s" and then will stall for 2 seconds.")
      runningContainersWithImageTag.foreach { container =>
        dockerClient.stopContainer(container.id(), 5)
      }
    }
    runningContainersWithImageTag
  }

  private def getRunningContainersWithImageIds(imageIds: Set[String]): Iterable[Container] = {
    dockerClient
      .listContainers(
        ListContainersParam.allContainers(),
        ListContainersParam.withStatusRunning())
      .asScala
      .filter(container => imageIds.contains(container.imageId()))
  }

  private def deleteImage(name: String): Unit = {
    try {
      dockerClient.removeImage(s"$name:$resolvedDockerImageTag")
    } catch {
      case e: RuntimeException =>
        logWarning(s"Failed to delete image $name:$resolvedDockerImageTag. There may be images leaking in the" +
          s" docker environment which are now stale and unused.", e)
    }
  }
}
