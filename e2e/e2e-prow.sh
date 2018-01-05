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

### This script is used by Kubernetes Test Infrastructure to run integration tests.
### See documenation at https://github.com/kubernetes/test-infra/tree/master/prow
### To run the integration tests yourself, use e2e/runner.sh.

set -ex

# Install basic dependencies
echo "deb http://http.debian.net/debian jessie-backports main" >> /etc/apt/sources.list
apt-get update && apt-get install -y curl wget git tar
apt-get install -t jessie-backports -y openjdk-8-jdk

# Set up config.
master=$(kubectl cluster-info | head -n 1 | grep -oE "https?://[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}(:[0-9]+)?")
repo="https://github.com/apache/spark"

# Special GCP project for publishing docker images built by test.
image_repo="gcr.io/spark-testing-191023"
cd "$(dirname "$0")"/../
./e2e/runner.sh -m $master -r $repo -i $image_repo -d cloud

# Copy out the junit xml files for consumption by k8s test-infra.
ls -1 ./integration-test/target/surefire-reports/*.xml | cat -n | while read n f; do cp "$f" "/workspace/_artifacts/junit_0$n.xml"; done
