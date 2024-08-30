// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.google.devtools.build.lib.packages;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.Label.RepoContext;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.skyframe.BzlLoadValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue;
import com.google.devtools.build.skyframe.SkyFunction;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;

/**
 * Implementation of --incompatible_autoload_externally.
 *
 * <p>The flag adds loads to external repository for rules and top-level symbols, or removes them.
 * This class prepares new environment for BzlCompileFunction, BzlLoadFunction and PackageFuntions.
 *
 * <p>Environment for BzlCompileFunction is prepared immediately during construction. Only names of
 * the symbols are needed and provided. Values of the symbols are set to None.
 *
 * <p>Environments for BzlLoadFunction and PackageFunctions are prepared in StarlarkBuilinsFunction.
 *
 * <p>Cycles are prevented by disallowing autoloads in repos that we autoload from and the repos
 * they depend on.
 */
public class AutoloadSymbols {
  // Following fields autoloadedSymbols, removedSymbols, partiallyRemovedSymbols,
  // reposDisallowingAutoloads are empty if autoloads aren't used
  // Symbols that aren't prefixed with '-', that get loaded from external repositories (in allowed
  // repositories).
  private final ImmutableList<String> autoloadedSymbols;
  // Symbols that are prefixed with '-', that are removed everywhere (from each repository)
  private final ImmutableList<String> removedSymbols;
  // Symbols that aren't prefixed with '+', that are removed from everywhere,
  // except in repositories where autoloaded symbols are defined
  private final ImmutableList<String> partiallyRemovedSymbols;

  // Repositories where autoloads shouldn't be used
  private final ImmutableSet<String> reposDisallowingAutoloads;

  // The environment formed by taking BazelStarlarkEnvironment's bzl environment and adding/removing
  // autoloaded symbols. The values of any added symbols are set to None (i.e. not actually loaded).
  // This is intended for BzlCompileFunction only.
  private final ImmutableMap<String, Object> uninjectedBuildBzlEnvWithAutoloads;

  // bzl environment where autoloads aren't used, uninjected (not loaded yet)
  private final ImmutableMap<String, Object> uninjectedBuildBzlEnvWithoutAutoloads;

  // Used for nicer error messages
  private final boolean bzlmodEnabled;
  private final boolean autoloadsEnabled;

  public AutoloadSymbols(RuleClassProvider ruleClassProvider, StarlarkSemantics semantics) {
    ImmutableList<String> symbolConfiguration =
        ImmutableList.copyOf(semantics.get(BuildLanguageOptions.INCOMPATIBLE_AUTOLOAD_EXTERNALLY));
    this.bzlmodEnabled = semantics.getBool(BuildLanguageOptions.ENABLE_BZLMOD);
    this.autoloadsEnabled = !symbolConfiguration.isEmpty();

    if (!autoloadsEnabled) {
      ImmutableMap<String, Object> originalBuildBzlEnv =
          ruleClassProvider.getBazelStarlarkEnvironment().getUninjectedBuildBzlEnv();
      this.uninjectedBuildBzlEnvWithAutoloads = originalBuildBzlEnv;
      this.uninjectedBuildBzlEnvWithoutAutoloads = originalBuildBzlEnv;
      this.reposDisallowingAutoloads = ImmutableSet.of();
      this.autoloadedSymbols = ImmutableList.of();
      this.removedSymbols = ImmutableList.of();
      this.partiallyRemovedSymbols = ImmutableList.of();
      return;
    }

    // Expand symbols given with @rules_foo
    symbolConfiguration =
        symbolConfiguration.stream()
            .flatMap(
                flag -> {
                  String prefix = "";
                  String flagWithoutPrefix = flag;
                  if (flag.startsWith("+") || flag.startsWith("-")) {
                    prefix = flag.substring(0, 1);
                    flagWithoutPrefix = flag.substring(1);
                  }
                  if (flagWithoutPrefix.startsWith("@")) {
                    return getAllSymbols(flagWithoutPrefix, prefix).stream();
                  } else {
                    return Stream.of(flag);
                  }
                })
            .collect(toImmutableList());

    // Validates the inputs
    Set<String> uniqueSymbols = new HashSet<>();
    for (String symbol : symbolConfiguration) {
      String symbolWithoutPrefix =
          symbol.startsWith("+") || symbol.startsWith("-") ? symbol.substring(1) : symbol;
      if (!uniqueSymbols.add(symbolWithoutPrefix)) {
        throw new IllegalStateException(
            String.format(
                "Duplicated symbol '%s' in --incompatible_autoload_externally",
                symbolWithoutPrefix));
      }
      if (!AUTOLOAD_CONFIG.containsKey(symbolWithoutPrefix)) {
        throw new IllegalStateException("Undefined symbol in --incompatible_autoload_externally");
      }
    }

    this.autoloadedSymbols = filterSymbols(symbolConfiguration, symbol -> !symbol.startsWith("-"));
    this.removedSymbols = filterSymbols(symbolConfiguration, symbol -> symbol.startsWith("-"));
    this.partiallyRemovedSymbols =
        filterSymbols(symbolConfiguration, symbol -> !symbol.startsWith("+"));

    this.reposDisallowingAutoloads =
        ImmutableSet.<String>builder()
            .addAll(PREDECLARED_REPOS_DISALLOWING_AUTOLOADS)
            .addAll(semantics.get(BuildLanguageOptions.REPOSITORIES_WITHOUT_AUTOLOAD))
            .build();

    ImmutableMap<String, Object> originalBuildBzlEnv =
        ruleClassProvider.getBazelStarlarkEnvironment().getUninjectedBuildBzlEnv();

    // Sets up environments for BzlCompile function
    this.uninjectedBuildBzlEnvWithAutoloads =
        modifyBuildBzlEnv(
            /* isWithAutoloads= */ true,
            originalBuildBzlEnv,
            /* newSymbols= */ autoloadedSymbols.stream()
                .collect(toImmutableMap(key -> key, key -> Starlark.NONE)));
    this.uninjectedBuildBzlEnvWithoutAutoloads =
        modifyBuildBzlEnv(
            /* isWithAutoloads= */ false, originalBuildBzlEnv, /* newSymbols= */ ImmutableMap.of());

    // Validate rdeps - this ensures that all the rules using a provider are also removed
    // Check what's still available in Bazel (some symbols might already be deleted)
    ImmutableSet<String> allAvailableSymbols =
        ImmutableSet.<String>builder()
            .addAll(uninjectedBuildBzlEnvWithoutAutoloads.keySet())
            .addAll(
                convertNativeStructToMap(
                        (StarlarkInfo) uninjectedBuildBzlEnvWithoutAutoloads.get("native"))
                    .keySet())
            .build();
    for (String symbol : partiallyRemovedSymbols) {
      ImmutableList<String> unsatisfiedRdeps =
          AUTOLOAD_CONFIG.get(symbol).getRdeps().stream()
              .filter(allAvailableSymbols::contains)
              .collect(toImmutableList());
      if (!unsatisfiedRdeps.isEmpty()) {
        throw new IllegalStateException(
            String.format(
                "Symbol in '%s' can't be removed, because it's still used by: %s",
                symbol, String.join(", ", unsatisfiedRdeps)));
      }
    }
  }

  /** An optimisation, checking is autoloads are used at all. */
  public boolean isEnabled() {
    return autoloadsEnabled;
  }

  /** Returns the environment for BzlCompile function */
  public ImmutableMap<String, Object> getUninjectedBuildBzlEnv(@Nullable Label key) {
    return autoloadsDisabledForRepo(key)
        ? uninjectedBuildBzlEnvWithoutAutoloads
        : uninjectedBuildBzlEnvWithAutoloads;
  }

  /** Check if autoloads shouldn't be used. */
  public boolean autoloadsDisabledForRepo(@Nullable Label key) {
    if (!autoloadsEnabled) {
      return true;
    }
    return key == null || autoloadsDisabledForRepo(key.getRepository().getName());
  }

  /**
   * Check if autoloads shouldn't be used in the given repository.
   *
   * <p>Autoloads aren't used for repos in {@link #PREDECLARED_REPOS_DISALLOWING_AUTOLOADS},
   * specified by the --repositories_without_autoloads flag or any of their immediate descendants
   * (parsing the cannonical repository name to check this).
   */
  public boolean autoloadsDisabledForRepo(String repo) {
    if (!autoloadsEnabled) {
      return true;
    }
    int separatorIndex = repo.contains("~") ? repo.indexOf("~") : repo.indexOf("+");
    return reposDisallowingAutoloads.contains(
        separatorIndex >= 0 ? repo.substring(0, separatorIndex) : repo);
  }

  /**
   * Modifies the environment for BzlLoad function (returned from StarlarkBuiltinsFunction).
   *
   * <p>{@code originalEnv} contains original environment and {@code newSymbols} is a map from new
   * symbol name to symbol's value. {@code isWithAutoloads} chooses the semantics, described in
   * details on --incompatible_autoload_externally flag.
   */
  public ImmutableMap<String, Object> modifyBuildBzlEnv(
      boolean isWithAutoloads,
      ImmutableMap<String, Object> originalEnv,
      ImmutableMap<String, Object> newSymbols) {
    if (isWithAutoloads) {
      return modifyBuildBzlEnv(originalEnv, /* add= */ newSymbols, /* remove= */ removedSymbols);
    } else {
      return modifyBuildBzlEnv(
          originalEnv, /* add= */ ImmutableMap.of(), /* remove= */ partiallyRemovedSymbols);
    }
  }

  /**
   * Creates modified environment that's used in BzlCompileFunction and StarlarkBuiltinsFunction.
   *
   * <p>It starts with the original environment. Adds the symbols to it or removes them.
   */
  private ImmutableMap<String, Object> modifyBuildBzlEnv(
      ImmutableMap<String, Object> originalEnv,
      ImmutableMap<String, Object> add,
      ImmutableList<String> remove) {
    Map<String, Object> envBuilder = new LinkedHashMap<>(originalEnv);
    Map<String, Object> nativeBindings =
        convertNativeStructToMap((StarlarkInfo) envBuilder.remove("native"));

    for (Map.Entry<String, Object> symbol : add.entrySet()) {
      if (AUTOLOAD_CONFIG.get(symbol.getKey()).isRule()) {
        nativeBindings.put(symbol.getKey(), symbol.getValue());
      } else {
        envBuilder.put(symbol.getKey(), symbol.getValue());
      }
    }
    for (String symbol : remove) {
      if (AUTOLOAD_CONFIG.get(symbol).isRule()) {
        nativeBindings.remove(symbol);
      } else {
        envBuilder.remove(symbol);
      }
    }
    envBuilder.put(
        "native", StructProvider.STRUCT.create(nativeBindings, "no native function or rule '%s'"));
    return ImmutableMap.copyOf(envBuilder);
  }

  /** Modifies the environment for Package function (returned from StarlarkBuiltinsFunction). */
  public ImmutableMap<String, Object> modifyBuildEnv(
      boolean isWithAutoloads,
      ImmutableMap<String, Object> originalEnv,
      ImmutableMap<String, Object> newSymbols) {
    final ImmutableMap<String, Object> add;
    if (isWithAutoloads) {
      add = newSymbols;
    } else {
      add = ImmutableMap.of();
    }
    Map<String, Object> envBuilder = new LinkedHashMap<>(originalEnv);
    for (Map.Entry<String, Object> symbol : add.entrySet()) {
      if (AUTOLOAD_CONFIG.get(symbol.getKey()).isRule()) {
        envBuilder.put(symbol.getKey(), symbol.getValue());
      }
    }
    for (String symbol : removedSymbols) {
      if (AUTOLOAD_CONFIG.get(symbol).isRule()) {
        envBuilder.remove(symbol);
      }
    }
    return ImmutableMap.copyOf(envBuilder);
  }

  private static ImmutableList<String> filterSymbols(
      ImmutableList<String> symbols, Predicate<String> when) {
    return symbols.stream()
        .filter(when)
        .map(
            symbol ->
                symbol.startsWith("+") || symbol.startsWith("-") ? symbol.substring(1) : symbol)
        .collect(toImmutableList());
  }

  private ImmutableList<String> getAllSymbols(String repository, String prefix) {
    return AUTOLOAD_CONFIG.entrySet().stream()
        .filter(entry -> entry.getValue().getLoadLabel().startsWith(repository + "//"))
        .map(entry -> prefix + entry.getKey())
        .collect(toImmutableList());
  }

  private static Map<String, Object> convertNativeStructToMap(StarlarkInfo struct) {
    LinkedHashMap<String, Object> destr = new LinkedHashMap<>();
    for (String field : struct.getFieldNames()) {
      destr.put(field, struct.getValue(field));
    }
    return destr;
  }

  /**
   * Returns a list of all the extra .bzl files that need to be loaded
   *
   * <p>Keys are coming from {@link AUTOLOAD_CONFIG} table.
   *
   * <p>Actual loading is done in {@link StarlarkBuiltinsValue} and then passed to {@link
   * #processLoads} for final processing. The parameter {@code autoloadValues} must correspond to
   * the map returned by * {@link #getLoadKeys}.
   */
  @Nullable
  public ImmutableMap<String, BzlLoadValue.Key> getLoadKeys(SkyFunction.Environment env)
      throws InterruptedException {
    RepositoryMappingValue repositoryMappingValue =
        (RepositoryMappingValue)
            env.getValue(RepositoryMappingValue.key(RepositoryName.BAZEL_TOOLS));

    if (repositoryMappingValue == null) {
      return null;
    }

    RepoContext repoContext =
        Label.RepoContext.of(
            RepositoryName.BAZEL_TOOLS, repositoryMappingValue.getRepositoryMapping());

    // Inject loads for rules and symbols removed from Bazel
    ImmutableMap.Builder<String, BzlLoadValue.Key> loadKeysBuilder =
        ImmutableMap.builderWithExpectedSize(autoloadedSymbols.size());
    for (String symbol : autoloadedSymbols) {
      loadKeysBuilder.put(symbol, AUTOLOAD_CONFIG.get(symbol).getKey(repoContext));
    }
    return loadKeysBuilder.buildOrThrow();
  }

  /**
   * Processes LoadedValues into a map of symbols
   *
   * <p>The parameter {@code autoloadValues} must correspond to the map returned by {@link
   * #getLoadKeys}. Actual loading is done in {@link StarlarkBuiltinsValue}.
   *
   * <p>Keys are coming from {@link AUTOLOAD_CONFIG} table.
   */
  public ImmutableMap<String, Object> processLoads(
      ImmutableMap<String, BzlLoadValue> autoloadValues) throws AutoloadException {
    if (autoloadValues.isEmpty()) {
      return ImmutableMap.of();
    }

    ImmutableMap.Builder<String, Object> newSymbols =
        ImmutableMap.builderWithExpectedSize(autoloadValues.size());
    String workspaceWarning =
        bzlmodEnabled
            ? ""
            : " Most likely you need to upgrade the version of rules repository in the"
                + " WORKSPACE file.";
    for (Map.Entry<String, BzlLoadValue> autoload : autoloadValues.entrySet()) {
      String symbol = autoload.getKey();
      // Check if the symbol is named differently in the bzl file than natively. Renames are rare:
      // Example is renaming native.ProguardSpecProvider to ProguardSpecInfo.
      String newName = AUTOLOAD_CONFIG.get(symbol).getNewName();
      if (newName == null) {
        newName = symbol;
      }
      BzlLoadValue v = autoload.getValue();
      Object symbolValue = v.getModule().getGlobal(newName);
      if (symbolValue == null) {
        throw new AutoloadException(
            String.format(
                "The toplevel symbol '%s' set by --incompatible_load_symbols_externally couldn't"
                    + " be loaded. '%s' not found in auto loaded '%s'.%s",
                symbol, newName, AUTOLOAD_CONFIG.get(symbol).getLoadLabel(), workspaceWarning));
      }
      newSymbols.put(symbol, symbolValue); // Exposed as old name
    }
    return newSymbols.buildOrThrow();
  }

  @Override
  public final int hashCode() {
    // These fields are used to generate all other private fields.
    // Thus, other fields don't need to be included in hash code.
    return Objects.hash(
        autoloadedSymbols, removedSymbols, partiallyRemovedSymbols, reposDisallowingAutoloads);
  }

  @Override
  public final boolean equals(Object that) {
    if (this == that) {
      return true;
    }
    if (that instanceof AutoloadSymbols) {
      AutoloadSymbols other = (AutoloadSymbols) that;
      // These fields are used to generate all other private fields.
      // Thus, other fields don't need to be included in comparison.
      return this.autoloadedSymbols.equals(other.autoloadedSymbols)
          && this.removedSymbols.equals(other.removedSymbols)
          && this.partiallyRemovedSymbols.equals(other.partiallyRemovedSymbols)
          && this.reposDisallowingAutoloads.equals(other.reposDisallowingAutoloads);
    }
    return false;
  }

  /** Configuration of a symbol */
  @AutoValue
  public abstract static class SymbolRedirect {

    public abstract String getLoadLabel();

    public abstract boolean isRule();

    @Nullable
    public abstract String getNewName();

    public abstract ImmutableSet<String> getRdeps();

    public BzlLoadValue.Key getKey(RepoContext bazelToolsRepoContext) throws InterruptedException {
      try {
        return BzlLoadValue.keyForBuild(
            Label.parseWithRepoContext(getLoadLabel(), bazelToolsRepoContext));
      } catch (LabelSyntaxException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** Indicates a problem performing automatic loads. */
  public static final class AutoloadException extends Exception {

    AutoloadException(String message) {
      super(message);
    }
  }

  private static SymbolRedirect ruleRedirect(String label) {
    return new AutoValue_AutoloadSymbols_SymbolRedirect(label, true, null, ImmutableSet.of());
  }

  private static SymbolRedirect symbolRedirect(String label, String... rdeps) {
    return new AutoValue_AutoloadSymbols_SymbolRedirect(
        label, false, null, ImmutableSet.copyOf(rdeps));
  }

  private static SymbolRedirect renamedSymbolRedirect(
      String label, String newName, String... rdeps) {
    return new AutoValue_AutoloadSymbols_SymbolRedirect(
        label, false, newName, ImmutableSet.copyOf(rdeps));
  }

  private static final String[] androidRules = {
    "aar_import", "android_binary", "android_library", "android_local_test", "android_sdk"
  };

  private static final ImmutableSet<String> PREDECLARED_REPOS_DISALLOWING_AUTOLOADS =
      ImmutableSet.of(
          "protobuf",
          "com_google_protobuf",
          "rules_android",
          "rules_cc",
          "rules_java",
          "rules_java_builtin",
          "rules_python",
          "rules_python_internal",
          "rules_sh",
          "apple_common",
          "bazel_skylib",
          "bazel_tools");

  private static final ImmutableMap<String, SymbolRedirect> AUTOLOAD_CONFIG =
      ImmutableMap.<String, SymbolRedirect>builder()
          .put(
              "CcSharedLibraryInfo",
              symbolRedirect(
                  "@rules_cc//cc/common:cc_shared_library_info.bzl", "cc_shared_library"))
          .put(
              "CcSharedLibraryHintInfo",
              symbolRedirect("@rules_cc//cc/common:cc_shared_library_hint_info.bzl", "cc_common"))
          .put(
              "cc_proto_aspect",
              symbolRedirect("@protobuf//bazel/common:cc_proto_library.bzl", "cc_proto_library"))
          .put(
              "ProtoInfo",
              symbolRedirect(
                  "@protobuf//bazel/common:proto_info.bzl",
                  "proto_library",
                  "cc_proto_library",
                  "cc_shared_library",
                  "java_lite_proto_library",
                  "java_proto_library",
                  "proto_lang_toolchain",
                  "java_binary",
                  "py_extension",
                  "proto_common_do_not_use"))
          .put(
              "proto_common_do_not_use", symbolRedirect("@protobuf//bazel/common:proto_common.bzl"))
          .put("cc_common", symbolRedirect("@rules_cc//cc/common:cc_common.bzl"))
          .put(
              "CcInfo",
              symbolRedirect(
                  "@rules_cc//cc/common:cc_info.bzl",
                  "cc_binary",
                  "cc_library",
                  "cc_test",
                  "cc_shared_library",
                  "cc_common",
                  "java_library",
                  "cc_proto_library",
                  "java_import",
                  "java_runtime",
                  "java_binary",
                  "objc_library",
                  "java_common",
                  "JavaInfo",
                  "py_extension",
                  "cc_import",
                  "objc_import",
                  "objc_library",
                  "cc_toolchain",
                  "PyCcLinkParamsProvider",
                  "py_library"))
          .put(
              "DebugPackageInfo",
              symbolRedirect("@rules_cc//cc/common:debug_package_info.bzl", "cc_binary", "cc_test"))
          .put(
              "CcToolchainConfigInfo",
              symbolRedirect(
                  "@rules_cc//cc/toolchains:cc_toolchain_config_info.bzl", "cc_toolchain"))
          .put("java_common", symbolRedirect("@rules_java//java/common:java_common.bzl"))
          .put(
              "JavaInfo",
              symbolRedirect(
                  "@rules_java//java/common:java_info.bzl",
                  "java_binary",
                  "java_library",
                  "java_test",
                  "java_proto_library",
                  "java_lite_proto_library",
                  "java_plugin",
                  "java_import",
                  "java_common"))
          .put(
              "JavaPluginInfo",
              symbolRedirect(
                  "@rules_java//java/common:java_plugin_info.bzl",
                  "java_plugin",
                  "java_library",
                  "java_binary",
                  "java_test"))
          .put(
              "ProguardSpecProvider",
              renamedSymbolRedirect(
                  "@rules_java//java/common:proguard_spec_info.bzl",
                  "ProguardSpecInfo",
                  "java_lite_proto_library",
                  "java_import",
                  "android_binary",
                  "android_library"))
          .put("android_common", symbolRedirect("@rules_android//rules:common.bzl"))
          .put(
              "AndroidIdeInfo", symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put("ApkInfo", symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "AndroidInstrumentationInfo",
              symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "AndroidResourcesInfo",
              symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "AndroidNativeLibsInfo",
              symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "AndroidApplicationResourceInfo",
              symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "AndroidBinaryNativeLibsInfo",
              symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "AndroidSdkInfo", symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "AndroidManifestInfo",
              symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "AndroidAssetsInfo",
              symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "AndroidLibraryAarInfo",
              symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "AndroidProguardInfo",
              symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "AndroidIdlInfo", symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "AndroidPreDexJarInfo",
              symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "AndroidCcLinkParamsInfo",
              symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "DataBindingV2Info",
              symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "AndroidLibraryResourceClassJarProvider",
              symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "AndroidFeatureFlagSet",
              symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "ProguardMappingInfo",
              symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "AndroidBinaryData",
              symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "BaselineProfileProvider",
              symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "AndroidNeverLinkLibrariesProvider",
              symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "AndroidOptimizedJarInfo",
              symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "AndroidDexInfo", symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "AndroidOptimizationInfo",
              symbolRedirect("@rules_android//rules:providers.bzl", androidRules))
          .put(
              "PyInfo",
              symbolRedirect(
                  "@rules_python//python:py_info.bzl", "py_binary", "py_test", "py_library"))
          .put(
              "PyRuntimeInfo",
              symbolRedirect(
                  "@rules_python//python:py_runtime_info.bzl",
                  "py_binary",
                  "py_test",
                  "py_library"))
          .put(
              "PyCcLinkParamsProvider",
              renamedSymbolRedirect(
                  "@rules_python//python:py_cc_link_params_info.bzl",
                  "PyCcLinkParamsInfo",
                  "py_binary",
                  "py_test",
                  "py_library"))
          .put("aar_import", ruleRedirect("@rules_android//rules:rules.bzl"))
          .put("android_binary", ruleRedirect("@rules_android//rules:rules.bzl"))
          .put("android_device_script_fixture", ruleRedirect("@rules_android//rules:rules.bzl"))
          .put("android_host_service_fixture", ruleRedirect("@rules_android//rules:rules.bzl"))
          .put("android_library", ruleRedirect("@rules_android//rules:rules.bzl"))
          .put("android_local_test", ruleRedirect("@rules_android//rules:rules.bzl"))
          .put("android_sdk", ruleRedirect("@rules_android//rules:rules.bzl"))
          .put("android_tools_defaults_jar", ruleRedirect("@rules_android//rules:rules.bzl"))
          .put("cc_binary", ruleRedirect("@rules_cc//cc:cc_binary.bzl"))
          .put("cc_import", ruleRedirect("@rules_cc//cc:cc_import.bzl"))
          .put("cc_library", ruleRedirect("@rules_cc//cc:cc_library.bzl"))
          .put("cc_proto_library", ruleRedirect("@protobuf//bazel:cc_proto_library.bzl"))
          .put("cc_shared_library", ruleRedirect("@rules_cc//cc:cc_shared_library.bzl"))
          .put("cc_test", ruleRedirect("@rules_cc//cc:cc_test.bzl"))
          .put("cc_toolchain", ruleRedirect("@rules_cc//cc/toolchains:cc_toolchain.bzl"))
          .put(
              "cc_toolchain_suite", ruleRedirect("@rules_cc//cc/toolchains:cc_toolchain_suite.bzl"))
          .put(
              "fdo_prefetch_hints", ruleRedirect("@rules_cc//cc/toolchains:fdo_prefetch_hints.bzl"))
          .put("fdo_profile", ruleRedirect("@rules_cc//cc/toolchains:fdo_profile.bzl"))
          .put("java_binary", ruleRedirect("@rules_java//java:java_binary.bzl"))
          .put("java_import", ruleRedirect("@rules_java//java:java_import.bzl"))
          .put("java_library", ruleRedirect("@rules_java//java:java_library.bzl"))
          .put(
              "java_lite_proto_library",
              ruleRedirect("@protobuf//bazel:java_lite_proto_library.bzl"))
          .put(
              "java_package_configuration",
              ruleRedirect("@rules_java//java/toolchains:java_package_configuration.bzl"))
          .put("java_plugin", ruleRedirect("@rules_java//java:java_plugin.bzl"))
          .put("java_proto_library", ruleRedirect("@protobuf//bazel:java_proto_library.bzl"))
          .put("java_runtime", ruleRedirect("@rules_java//java/toolchains:java_runtime.bzl"))
          .put("java_test", ruleRedirect("@rules_java//java:java_test.bzl"))
          .put("java_toolchain", ruleRedirect("@rules_java//java/toolchains:java_toolchain.bzl"))
          .put("memprof_profile", ruleRedirect("@rules_cc//cc/toolchains:memprof_profile.bzl"))
          .put("objc_import", ruleRedirect("@rules_cc//cc:objc_import.bzl"))
          .put("objc_library", ruleRedirect("@rules_cc//cc:objc_library.bzl"))
          .put(
              "propeller_optimize", ruleRedirect("@rules_cc//cc/toolchains:propeller_optimize.bzl"))
          .put(
              "proto_lang_toolchain",
              ruleRedirect("@protobuf//bazel/toolchain:proto_lang_toolchain.bzl"))
          .put("proto_library", ruleRedirect("@protobuf//bazel:proto_library.bzl"))
          .put("py_binary", ruleRedirect("@rules_python//python:py_binary.bzl"))
          .put("py_library", ruleRedirect("@rules_python//python:py_library.bzl"))
          .put("py_runtime", ruleRedirect("@rules_python//python:py_runtime.bzl"))
          .put("py_test", ruleRedirect("@rules_python//python:py_test.bzl"))
          .put("sh_binary", ruleRedirect("@rules_sh//sh:sh_binary.bzl"))
          .put("sh_library", ruleRedirect("@rules_sh//sh:sh_library.bzl"))
          .put("sh_test", ruleRedirect("@rules_sh//sh:sh_test.bzl"))
          .put("available_xcodes", ruleRedirect("@apple_support//xcode:available_xcodes.bzl"))
          .put("xcode_config", ruleRedirect("@apple_support//xcode:xcode_config.bzl"))
          .put("xcode_config_alias", ruleRedirect("@apple_support//xcode:xcode_config_alias.bzl"))
          .put("xcode_version", ruleRedirect("@apple_support//xcode:xcode_version.bzl"))
          // this redirect doesn't exists and probably never will, we still need a configuration for
          // it, so that it can be removed from Bazels <= 7 if needed
          .put(
              "apple_common",
              symbolRedirect("@apple_support//lib:apple_common.bzl", "objc_import", "objc_library"))
          .buildOrThrow();
}
