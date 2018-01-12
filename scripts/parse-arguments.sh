#!/usr/bin/env bash

#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

set -ex
TEST_ROOT_DIR=$(git rev-parse --show-toplevel)
BRANCH="master"
SPARK_REPO="https://github.com/apache/spark"
SPARK_REPO_LOCAL_DIR="$TEST_ROOT_DIR/target/spark"
UNPACKED_SPARK_TGZ="$TEST_ROOT_DIR/target/spark-dist-unpacked"
IMAGE_TAG_OUTPUT_FILE="$TEST_ROOT_DIR/target/image-tag.txt"
DEPLOY_MODE=minikube
IMAGE_REPO="docker.io/kubespark"
SKIP_BUILDING_IMAGES=false
SPARK_TGZ="N/A"
IMAGE_TAG="N/A"

# Parse arguments
while (( "$#" )); do
  case $1 in
    --spark-branch)
      BRANCH="$2"
      shift
      ;;
    --spark-repo)
      SPARK_REPO="$2"
      shift
      ;;
    --spark-repo-local-dir)
      SPARK_REPO_LOCAL_DIR="$2"
      shift
      ;;
    --unpacked-spark-tgz)
      UNPACKED_SPARK_TGZ="$2"
      shift
      ;;
    --image-repo)
      IMAGE_REPO="$2"
      shift
      ;;
    --image-tag)
      IMAGE_TAG="$2"
      shift
      ;;
    --image-tag-output-file)
      IMAGE_TAG_OUTPUT_FILE="$2"
      shift
      ;;
    --deploy-mode)
      DEPLOY_MODE="$2"
      shift
      ;;
    --spark-tgz)
      SPARK_TGZ="$2"
      shift
      ;;
    --skip-building-images)
      SKIP_BUILDING_IMAGES="$2"
      shift
      ;;
    *)
      break
      ;;
  esac
  shift
done

