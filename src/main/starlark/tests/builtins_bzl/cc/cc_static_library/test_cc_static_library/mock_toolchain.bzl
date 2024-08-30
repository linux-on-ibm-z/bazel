# Copyright 2024 The Bazel Authors. All rights reserved.
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

"""Mock toolchains for starlark tests for cc_static_library"""

load("@bazel_tools//tools/build_defs/cc:action_names.bzl", "ACTION_NAMES")
load("@rules_testing//lib:util.bzl", "util")
load(
    "//tools/cpp:cc_toolchain_config_lib.bzl",
    "action_config",
    "artifact_name_pattern",
    "env_entry",
    "env_set",
    "feature",
    "flag_group",
    "flag_set",
    "tool",
)

def _mock_cc_toolchain_config_impl(ctx):
    return cc_common.create_cc_toolchain_config_info(
        ctx = ctx,
        action_configs = [
            action_config(
                action_name = ACTION_NAMES.cpp_link_static_library,
                enabled = True,
                tools = [tool(path = "/usr/bin/my-ar")],
            ),
        ] + (
            [
                action_config(
                    action_name = ACTION_NAMES.validate_static_library,
                    tools = [tool(path = "validate_static_library.sh")],
                ),
            ] if ctx.attr.provide_validate_static_library else []
        ),
        features = [
            feature(
                name = "archiver_flags",
                enabled = True,
                env_sets = [
                    env_set(
                        actions = [ACTION_NAMES.cpp_link_static_library],
                        env_entries = [
                            env_entry(
                                key = "MY_KEY",
                                value = "my_value",
                            ),
                        ],
                    ),
                ],
                flag_sets = [
                    flag_set(
                        actions = [ACTION_NAMES.cpp_link_static_library],
                        flag_groups = [
                            flag_group(flags = ["abc"]),
                            flag_group(
                                flags = ["/MY_OUT:%{output_execpath}"],
                                expand_if_available = "output_execpath",
                            ),
                        ],
                    ),
                ],
            ),
            feature(
                name = "symbol_check",
                flag_sets = [
                    flag_set(
                        actions = [ACTION_NAMES.cpp_link_static_library],
                        flag_groups = [
                            flag_group(flags = ["--check-symbols"]),
                        ],
                    ),
                ],
                implies = [ACTION_NAMES.validate_static_library] if ctx.attr.provide_validate_static_library else [],
            ),
        ],
        artifact_name_patterns = [
            artifact_name_pattern(
                category_name = "static_library",
                prefix = "prefix",
                extension = ".lib",
            ),
        ],
        toolchain_identifier = "mock_toolchain",
        host_system_name = "local",
        target_system_name = "local",
        target_cpu = "local",
        target_libc = "local",
        compiler = "compiler",
    )

_mock_cc_toolchain_config = rule(
    implementation = _mock_cc_toolchain_config_impl,
    attrs = {
        "provide_validate_static_library": attr.bool(mandatory = True),
    },
    provides = [CcToolchainConfigInfo],
    doc = "Mock toolchain for cc_static_library tests",
)

def mock_cc_toolchain(name, provide_validate_static_library = True):
    """Creates a mock cc_toolchain for testing cc_static_library.

    Args:
        name: The name of the cc_toolchain.
        provide_validate_static_library: Whether to provide the
          validate_static_library action_config.
    """
    archiver = util.empty_file(
        name = name + "_my_ar",
    )

    _mock_cc_toolchain_config(
        name = name + "_config",
        provide_validate_static_library = provide_validate_static_library,
    )

    empty = name + "_empty"
    native.filegroup(
        name = empty,
    )

    all_files = name + "_all_files"
    native.filegroup(
        name = all_files,
        srcs = [archiver],
    )

    native.cc_toolchain(
        name = name + "_cc_toolchain",
        toolchain_config = name + "_config",
        all_files = all_files,
        dwp_files = empty,
        compiler_files = empty,
        linker_files = empty,
        objcopy_files = empty,
        strip_files = empty,
    )

    native.toolchain(
        name = name,
        toolchain = name + "_cc_toolchain",
        toolchain_type = "@bazel_tools//tools/cpp:toolchain_type",
    )
