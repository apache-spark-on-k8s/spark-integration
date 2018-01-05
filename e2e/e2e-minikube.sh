#!/bin/bash

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

### This script can be used to run integration tests locally on minikube.
### Requirements: minikube v0.23+ with the DNS addon enabled, and kubectl configured to point to it.

set -ex

### Basic Validation ###
if [ ! -d "integration-test" ]; then
  echo "This script must be invoked from the top-level directory of the integration-tests repository"
  usage
  exit 1
fi

# Set up config.
master=$(kubectl cluster-info | head -n 1 | grep -oE "https?://[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}(:[0-9]+)?")
repo="https://github.com/apache/spark"
image_repo=test

# Run tests in minikube mode.
./e2e/runner.sh -m $master -r $repo -i $image_repo -d minikube
