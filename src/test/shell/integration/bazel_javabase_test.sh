#!/bin/bash
# Copyright 2016 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Tests that our --host_javabase startup selection algorithm works.

# Load test environment
source $(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/testenv.sh \
  || { echo "testenv.sh not found!" >&2; exit 1; }

put_bazel_on_path
create_and_cd_client

function test_use_depot_javabase() {
  bazel --batch version >& $TEST_log || fail "Couldn't run Bazel"
  expect_not_log "Couldn't find java at"
  expect_not_log "Problem with java installation"
}

function test_fallback_depot_javabase() {
  bazel --batch --host_javabase=/does/not/exist version >& $TEST_log ||
    (expect_log "Couldn't find java at" &&
     expect_not_log "Problem with java installation")
}

run_suite "Tests of specifying custom javabase."
