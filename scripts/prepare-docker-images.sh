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
SCRIPTS_DIR=$(dirname $0)
source "$SCRIPTS_DIR/parse-arguments.sh" "$@"

if [[ $IMAGE_TAG == "N/A" ]];
then
  IMAGE_TAG=$(uuidgen);
fi

$SCRIPTS_DIR/write-docker-tag.sh --image-tag $IMAGE_TAG --image-tag-output-file $IMAGE_TAG_OUTPUT_FILE

if [ ! -d "$UNPACKED_SPARK_TGZ" ];
then
  echo "No unpacked distribution was found at $UNPACKED_SPARK_TGZ. Please run clone-spark.sh and build-spark.sh first." && exit 1;
fi

FILE_SERVER_IMAGE="$IMAGE_REPO/spark-examples-file-server:$IMAGE_TAG"
FILE_SERVER_BUILD_DIR="$TEST_ROOT_DIR/docker-file-server"
rm -rf $FILE_SERVER_BUILD_DIR/jars
mkdir -p $FILE_SERVER_BUILD_DIR/jars
cp $UNPACKED_SPARK_TGZ/examples/jars/spark-examples*.jar $FILE_SERVER_BUILD_DIR/jars/.
cd $UNPACKED_SPARK_TGZ
if  [[ $DEPLOY_MODE == cloud ]] ;
then
  docker build -t $FILE_SERVER_IMAGE "$FILE_SERVER_BUILD_DIR"
  $UNPACKED_SPARK_TGZ/bin/docker-image-tool.sh -r $IMAGE_REPO -t $IMAGE_TAG build
  if  [[ $IMAGE_REPO == gcr.io* ]] ;
  then
    gcloud docker -- push $IMAGE_REPO/spark:$IMAGE_TAG && \
    gcloud docker -- push $FILE_SERVER_IMAGE
  else
    $UNPACKED_SPARK_TGZ/bin/docker-image-tool.sh -r $IMAGE_REPO -t $IMAGE_TAG push
    docker push $FILE_SERVER_IMAGE
  fi
else
  # -m option for minikube.
  eval $(minikube docker-env)
  docker build -t $FILE_SERVER_IMAGE $FILE_SERVER_BUILD_DIR
  $UNPACKED_SPARK_TGZ/bin/docker-image-tool.sh -m -r $IMAGE_REPO -t $IMAGE_TAG build
fi
