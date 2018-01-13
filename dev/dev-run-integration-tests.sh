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

TEST_ROOT_DIR=$(git rev-parse --show-toplevel)
BRANCH="master"
SPARK_REPO="https://github.com/apache/spark"
SPARK_REPO_LOCAL_DIR="$TEST_ROOT_DIR/target/spark"
DEPLOY_MODE=minikube
IMAGE_REPO="docker.io/kubespark"
SKIP_BUILDING_IMAGES=false
SPARK_TGZ="N/A"
IMAGE_TAG="N/A"
MAVEN_ARGS=()
SPARK_MASTER=

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
    --image-repo)
      IMAGE_REPO="$2"
      shift
      ;;
    --image-tag)
      IMAGE_TAG="$2"
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
    --maven-args)
      MAVEN_ARGS=("$2")
      shift
      ;;
    --skip-building-images)
      SKIP_BUILDING_IMAGES=true
      ;;
    *)
      break
      ;;
  esac
  shift
done

if [[ $SPARK_TGZ == "N/A" ]];
then
  echo "Cloning $SPARK_REPO into $SPARK_REPO_LOCAL_DIR and checking out $BRANCH."

  # clone spark distribution if needed.
  if [ -d "$SPARK_REPO_LOCAL_DIR" ];
  then
    (cd $SPARK_REPO_LOCAL_DIR && git fetch origin $branch);
  else
    mkdir -p $SPARK_REPO_LOCAL_DIR;
    git clone -b $BRANCH --single-branch $SPARK_REPO $SPARK_REPO_LOCAL_DIR;
  fi
  cd $SPARK_REPO_LOCAL_DIR
  git checkout -B $BRANCH origin/$branch
  ./dev/make-distribution.sh --tgz -Phadoop-2.7 -Pkubernetes -DskipTests;
  SPARK_TGZ=$(find $SPARK_REPO_LOCAL_DIR -name spark-*.tgz)
  echo "Built Spark TGZ at $SPARK_TGZ".
  cd -
fi

cd $TEST_ROOT_DIR

if [ -z $SPARK_MASTER ];
then
  build/mvn integration-test \
    -Dspark.kubernetes.test.sparkTgz=$SPARK_TGZ \
    -Dspark.kubernetes.test.skipBuildingImages=$SKIP_BUILDING_IMAGES \
    -Dspark.kubernetes.test.imageTag=$IMAGE_TAG \
    -Dspark.kubernetes.test.imageRepo=$IMAGE_REPO \
    -Dspark.kubernetes.test.deployMode=$DEPLOY_MODE \
    "${MAVEN_ARGS[@]/#/-}";
else
  build/mvn integration-test \
    -Dspark.kubernetes.test.sparkTgz=$SPARK_TGZ \
    -Dspark.kubernetes.test.skipBuildingImages=$SKIP_BUILDING_IMAGES \
    -Dspark.kubernetes.test.imageTag=$IMAGE_TAG \
    -Dspark.kubernetes.test.imageRepo=$IMAGE_REPO \
    -Dspark.kubernetes.test.deployMode=$DEPLOY_MODE \
    -Dspark.kubernetes.test.master=$SPARK_MASTER \
    "${MAVEN_ARGS[@]/#/-}";
fi
