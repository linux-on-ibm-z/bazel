#!/bin/bash
#
# Copyright 2017 The Bazel Authors. All rights reserved.
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
#
# Test the local disk cache
#

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

function test_local_action_cache() {
  local cache="${TEST_TMPDIR}/cache"
  local execution_file="${TEST_TMPDIR}/run.log"
  local input_file="foo.in"
  local output_file="bazel-genfiles/foo.txt"
  local flags="--disk_cache=$cache"

  rm -rf $cache
  mkdir $cache

  # No sandboxing, side effect is needed to detect action execution
  cat > BUILD <<EOF
genrule(
    name = "foo",
    cmd = "echo run > $execution_file && cat \$< >\$@",
    srcs = ["$input_file"],
    outs = ["foo.txt"],
    tags = ["no-sandbox"],
)
EOF

  # CAS is empty, cache miss
  echo 0 >"${execution_file}"
  echo 1 >"${input_file}"
  bazel build $flags :foo &> $TEST_log || fail "Build failed"
  assert_equals "1" $(cat "${output_file}")
  assert_equals "run" $(cat "${execution_file}")

  # CAS doesn't have output for this input, cache miss
  echo 0 >"${execution_file}"
  echo 2 >"${input_file}"
  bazel build $flags :foo &> $TEST_log || fail "Build failed"
  assert_equals "2" $(cat "${output_file}")
  assert_equals "run" $(cat "${execution_file}")

  # Cache hit, no action run/no side effect
  echo 0 >"${execution_file}"
  echo 1 >"${input_file}"
  bazel build $flags :foo &> $TEST_log || fail "Build failed"
  assert_equals "1" $(cat "${output_file}")
  assert_equals "0" $(cat "${execution_file}")
}

function test_input_directories_in_external_repo_with_sibling_repository_layout() {
  create_new_workspace
  l=$TEST_TMPDIR/l
  mkdir -p "$l/dir"
  touch "$l/REPO.bazel"
  touch "$l/dir/f"
  cat > "$l/BUILD" <<'EOF'
exports_files(["dir"])
EOF

  cat > MODULE.bazel <<EOF
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(name="l", path="$l")
EOF

  cat > BUILD <<'EOF'
genrule(name="g", srcs=["@l//:dir"], outs=["go"], cmd="find $< > $@")
EOF

  bazel build \
    --experimental_sibling_repository_layout  \
    --disk_cache="$TEST_TMPDIR/cache" \
    //:g || fail "build failed"

}

function test_cache_hit_on_source_edit_after_test_failure() {
  # Regression test for https://github.com/bazelbuild/bazel/issues/11057.

  local -r CACHE_DIR="${TEST_TMPDIR}/cache"
  rm -rf "$CACHE_DIR"

  mkdir -p a
  cat > a/BUILD <<'EOF'
sh_test(
    name = "test",
    srcs = ["test.sh"],
)
EOF
  echo "exit 0" > a/test.sh
  chmod +x a/test.sh

  # Populate the cache with a passing test.
  bazel test --disk_cache="$CACHE_DIR" //a:test >& $TEST_log \
    || fail "Expected test to pass"

  # Turn the test into a failing one.
  echo "exit 1" > a/test.sh

  # Check that the test fails.
  bazel test --disk_cache="$CACHE_DIR" //a:test >& $TEST_log \
    && fail "Expected test to fail"

  # Turn the test into a passing one again.
  echo "exit 0" > a/test.sh

  # Check that we hit the previously populated cache.
  bazel test --disk_cache="$CACHE_DIR" //a:test >& $TEST_log \
    || fail "Expected test to pass"
  expect_log "(cached) PASSED"
}

run_suite "disk cache test"
