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

import java.nio.file.Paths

import io.fabric8.kubernetes.client.{ConfigBuilder, DefaultKubernetesClient}

import org.apache.commons.lang3.SystemUtils
import org.apache.spark.deploy.k8s.integrationtest.{Logging, ProcessUtils}

// TODO support windows
private[spark] object Minikube extends Logging {
  private val MINIKUBE_EXECUTABLE_DEST = if (SystemUtils.IS_OS_MAC_OSX) {
    Paths.get("target", "minikube-bin", "darwin-amd64", "minikube").toFile
  } else if (SystemUtils.IS_OS_WINDOWS) {
    throw new IllegalStateException("Executing Minikube based integration tests not yet " +
      " available on Windows.")
  } else {
    Paths.get("target", "minikube-bin", "linux-amd64", "minikube").toFile
  }

  private val EXPECTED_DOWNLOADED_MINIKUBE_MESSAGE = "Minikube is not downloaded, expected at " +
    s"${MINIKUBE_EXECUTABLE_DEST.getAbsolutePath}"

  private val MINIKUBE_STARTUP_TIMEOUT_SECONDS = 60

  // NOTE: This and the following methods are synchronized to prevent deleteMinikube from
  // destroying the minikube VM while other methods try to use the VM.
  // Such a race condition can corrupt the VM or some VM provisioning tools like VirtualBox.
  def startMinikube(): Unit = synchronized {
    assert(MINIKUBE_EXECUTABLE_DEST.exists(), EXPECTED_DOWNLOADED_MINIKUBE_MESSAGE)
    if (getMinikubeStatus != MinikubeStatus.RUNNING) {
      executeMinikube("start", "--memory", "6000", "--cpus", "8")
    } else {
      logInfo("Minikube is already started.")
    }
  }

  def getMinikubeIp: String = synchronized {
    assert(MINIKUBE_EXECUTABLE_DEST.exists(), EXPECTED_DOWNLOADED_MINIKUBE_MESSAGE)
    val outputs = executeMinikube("ip")
      .filter(_.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))
    assert(outputs.size == 1, "Unexpected amount of output from minikube ip")
    outputs.head
  }

  def getMinikubeStatus: MinikubeStatus.Value = synchronized {
    assert(MINIKUBE_EXECUTABLE_DEST.exists(), EXPECTED_DOWNLOADED_MINIKUBE_MESSAGE)
    val statusString = executeMinikube("status")
      .filter(_.contains("minikube: "))
      .head
      .replaceFirst("minikube: ", "")
    MinikubeStatus.unapply(statusString)
        .getOrElse(throw new IllegalStateException(s"Unknown status $statusString"))
  }

  def getDockerEnv: Map[String, String] = synchronized {
    assert(MINIKUBE_EXECUTABLE_DEST.exists(), EXPECTED_DOWNLOADED_MINIKUBE_MESSAGE)
    executeMinikube("docker-env", "--shell", "bash")
        .filter(_.startsWith("export"))
        .map(_.replaceFirst("export ", "").split('='))
        .map(arr => (arr(0), arr(1).replaceAllLiterally("\"", "")))
        .toMap
  }

  def deleteMinikube(): Unit = synchronized {
    assert(MINIKUBE_EXECUTABLE_DEST.exists, EXPECTED_DOWNLOADED_MINIKUBE_MESSAGE)
    if (getMinikubeStatus != MinikubeStatus.NONE) {
      executeMinikube("delete")
    } else {
      logInfo("Minikube was already not running.")
    }
  }

  def getKubernetesClient: DefaultKubernetesClient = synchronized {
    val kubernetesMaster = s"https://${getMinikubeIp}:8443"
    val userHome = System.getProperty("user.home")
    val kubernetesConf = new ConfigBuilder()
      .withApiVersion("v1")
      .withMasterUrl(kubernetesMaster)
      .withCaCertFile(Paths.get(userHome, ".minikube", "ca.crt").toFile.getAbsolutePath)
      .withClientCertFile(Paths.get(userHome, ".minikube", "apiserver.crt").toFile.getAbsolutePath)
      .withClientKeyFile(Paths.get(userHome, ".minikube", "apiserver.key").toFile.getAbsolutePath)
      .build()
    new DefaultKubernetesClient(kubernetesConf)
  }

  def executeMinikubeSsh(command: String): Unit = {
    executeMinikube("ssh", command)
  }

  private def executeMinikube(action: String, args: String*): Seq[String] = {
    if (!MINIKUBE_EXECUTABLE_DEST.canExecute) {
      if (!MINIKUBE_EXECUTABLE_DEST.setExecutable(true)) {
        throw new IllegalStateException("Failed to make the Minikube binary executable.")
      }
    }
    ProcessUtils.executeProcess(Array(MINIKUBE_EXECUTABLE_DEST.getAbsolutePath, action) ++ args,
      MINIKUBE_STARTUP_TIMEOUT_SECONDS)
  }
}

private[spark] object MinikubeStatus extends Enumeration {

  // The following states are listed according to
  // https://github.com/docker/machine/blob/master/libmachine/state/state.go.
  val STARTING = status("Starting")
  val RUNNING = status("Running")
  val PAUSED = status("Paused")
  val STOPPING = status("Stopping")
  val STOPPED = status("Stopped")
  val ERROR = status("Error")
  val TIMEOUT = status("Timeout")
  val SAVED = status("Saved")
  val NONE = status("")

  def status(value: String): Value = new Val(nextId, value)
  def unapply(s: String): Option[Value] = values.find(s == _.toString)
}
