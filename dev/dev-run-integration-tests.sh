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
DEPLOY_MODE="minikube"
IMAGE_REPO="docker.io/kubespark"
SPARK_TGZ="N/A"
IMAGE_TAG="N/A"
SPARK_MASTER=
NAMESPACE=
SERVICE_ACCOUNT=

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
    --spark-master)
      SPARK_MASTER="$2"
      shift
      ;;
    --namespace)
      NAMESPACE="$2"
      shift
      ;;
    --service-account)
      SERVICE_ACCOUNT="$2"
      shift
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
    (cd $SPARK_REPO_LOCAL_DIR && git fetch origin $BRANCH);
  else
    mkdir -p $SPARK_REPO_LOCAL_DIR;
    git clone -b $BRANCH --single-branch $SPARK_REPO $SPARK_REPO_LOCAL_DIR;
  fi
  cd $SPARK_REPO_LOCAL_DIR
  git checkout -B $BRANCH origin/$BRANCH
  ./dev/make-distribution.sh --tgz -Phadoop-2.7 -Pkubernetes -DskipTests;
  SPARK_TGZ=$(find $SPARK_REPO_LOCAL_DIR -name spark-*.tgz)
  echo "Built Spark TGZ at $SPARK_TGZ".
  cd -
fi

cd $TEST_ROOT_DIR

properties=(
  -Dspark.kubernetes.test.sparkTgz=$SPARK_TGZ \
  -Dspark.kubernetes.test.imageTag=$IMAGE_TAG \
  -Dspark.kubernetes.test.imageRepo=$IMAGE_REPO \
  -Dspark.kubernetes.test.deployMode=$DEPLOY_MODE
)

if [ -n $NAMESPACE ];
then
  properties=( ${properties[@]} -Dspark.kubernetes.test.namespace=$NAMESPACE )
fi

if [ -n $SERVICE_ACCOUNT ];
then
  properties=( ${properties[@]} -Dspark.kubernetes.test.serviceAccountName=$SERVICE_ACCOUNT )
fi

if [ -n $SPARK_MASTER ];
then
  properties=( ${properties[@]} -Dspark.kubernetes.test.master=$SPARK_MASTER )
fi

build/mvn integration-test ${properties[@]}
