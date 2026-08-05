package sh.zolt.cli.command.testcmd;

import sh.zolt.build.BuildResult;
import sh.zolt.build.CompileDiagnostics;
import sh.zolt.build.testruntime.compile.TestCompileResult;
import sh.zolt.build.testruntime.TestRunResult;
import sh.zolt.cli.command.CommandAttributeKeys;
import sh.zolt.cli.command.CommandJavacWorkerAttributes;
import sh.zolt.test.TestSelection;
import sh.zolt.workspace.service.WorkspaceTestCompileResult;
import sh.zolt.workspace.service.WorkspaceTestResult;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CommandTestAttributes {
    private CommandTestAttributes() {
    }

    public static Map<String, String> testRun(TestRunResult result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(CommandAttributeKeys.MAIN_SOURCE_FILES, Integer.toString(result.compileResult().buildResult().sourceCount()));
        attributes.put(CommandAttributeKeys.TEST_SOURCE_FILES, Integer.toString(result.compileResult().sourceCount()));
        attributes.put(CommandAttributeKeys.TEST_RESOURCE_FILES, Integer.toString(result.compileResult().resourceCount()));
        attributes.put(CommandAttributeKeys.MAIN_COMPILATION_SKIPPED, Boolean.toString(result.compileResult().buildResult().mainCompilationSkipped()));
        attributes.put(CommandAttributeKeys.MAIN_COMPILATION_MODE, result.compileResult().buildResult().mainCompilationMode());
        attributes.put(CommandAttributeKeys.MAIN_INCREMENTAL_FALLBACK_REASON, result.compileResult().buildResult().mainIncrementalFallbackReason());
        attributes.put(CommandAttributeKeys.TEST_COMPILATION_SKIPPED, Boolean.toString(result.compileResult().testCompilationSkipped()));
        attributes.put(CommandAttributeKeys.TEST_COMPILATION_MODE, result.compileResult().testCompilationMode());
        attributes.put(CommandAttributeKeys.TEST_INCREMENTAL_FALLBACK_REASON, result.compileResult().testIncrementalFallbackReason());
        addMainCompileDiagnostics(attributes, result.compileResult().buildResult().mainCompileDiagnostics());
        addTestCompileDiagnostics(attributes, result.compileResult().testCompileDiagnostics());
        attributes.put(CommandAttributeKeys.TEST_RUNNER, result.testRunner());
        attributes.put(CommandAttributeKeys.TEST_RUNTIME_CLASSPATH_ENTRIES, Integer.toString(result.testRuntimeClasspathEntries()));
        attributes.put(CommandAttributeKeys.TEST_LAUNCHER_CLASSPATH_ENTRIES, Integer.toString(result.testLauncherClasspathEntries()));
        attributes.put(CommandAttributeKeys.TEST_DISCOVERY_SCAN_ROOTS, Integer.toString(result.testDiscoveryScanRoots()));
        addTestWorkerAttributes(
                attributes,
                result.testWorkerStarts(),
                result.testWorkerRequests());
        addTestSelectionAttributes(attributes, result.testSelection());
        attributes.put(CommandAttributeKeys.TEST_JVM_ARGS, Integer.toString(result.testJvmArguments().values().size()));
        addMainFingerprintAttributes(attributes, result.compileResult().buildResult());
        addTestFingerprintAttributes(attributes, result.compileResult());
        addTestRunnerTimingAttributes(attributes, result);
        addSlowTestEvidenceAttributes(attributes);
        attributes.put(CommandAttributeKeys.OUTPUT_BYTES, Integer.toString(result.output().length()));
        return attributes;
    }

    public static Map<String, String> testCompile(TestCompileResult result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(CommandAttributeKeys.MAIN_SOURCE_FILES, Integer.toString(result.buildResult().sourceCount()));
        attributes.put(CommandAttributeKeys.TEST_SOURCE_FILES, Integer.toString(result.sourceCount()));
        attributes.put(CommandAttributeKeys.TEST_RESOURCE_FILES, Integer.toString(result.resourceCount()));
        attributes.put(CommandAttributeKeys.MAIN_COMPILATION_SKIPPED, Boolean.toString(result.buildResult().mainCompilationSkipped()));
        attributes.put(CommandAttributeKeys.MAIN_COMPILATION_MODE, result.buildResult().mainCompilationMode());
        attributes.put(CommandAttributeKeys.MAIN_INCREMENTAL_FALLBACK_REASON, result.buildResult().mainIncrementalFallbackReason());
        attributes.put(CommandAttributeKeys.TEST_COMPILATION_SKIPPED, Boolean.toString(result.testCompilationSkipped()));
        attributes.put(CommandAttributeKeys.TEST_COMPILATION_MODE, result.testCompilationMode());
        attributes.put(CommandAttributeKeys.TEST_INCREMENTAL_FALLBACK_REASON, result.testIncrementalFallbackReason());
        addMainCompileDiagnostics(attributes, result.buildResult().mainCompileDiagnostics());
        addTestCompileDiagnostics(attributes, result.testCompileDiagnostics());
        addMainFingerprintAttributes(attributes, result.buildResult());
        addTestFingerprintAttributes(attributes, result);
        return attributes;
    }

    public static Map<String, String> testExecution(TestRunResult result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(CommandAttributeKeys.TEST_RUNNER, result.testRunner());
        attributes.put(CommandAttributeKeys.TEST_RUNTIME_CLASSPATH_ENTRIES, Integer.toString(result.testRuntimeClasspathEntries()));
        attributes.put(CommandAttributeKeys.TEST_LAUNCHER_CLASSPATH_ENTRIES, Integer.toString(result.testLauncherClasspathEntries()));
        attributes.put(CommandAttributeKeys.TEST_DISCOVERY_SCAN_ROOTS, Integer.toString(result.testDiscoveryScanRoots()));
        addTestWorkerAttributes(
                attributes,
                result.testWorkerStarts(),
                result.testWorkerRequests());
        addTestSelectionAttributes(attributes, result.testSelection());
        attributes.put(CommandAttributeKeys.TEST_JVM_ARGS, Integer.toString(result.testJvmArguments().values().size()));
        addTestRunnerTimingAttributes(attributes, result);
        addSlowTestEvidenceAttributes(attributes);
        attributes.put(CommandAttributeKeys.OUTPUT_BYTES, Integer.toString(result.output().length()));
        return attributes;
    }

    public static Map<String, String> workspaceTest(WorkspaceTestResult result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(CommandAttributeKeys.MEMBERS, Integer.toString(result.members().size()));
        attributes.put(CommandAttributeKeys.INCLUDED_MEMBERS, Integer.toString(result.includedMemberCount()));
        attributes.put(CommandAttributeKeys.SELECTED_MEMBERS, Integer.toString(result.selectedMemberCount()));
        attributes.put(CommandAttributeKeys.DEPENDENCY_MEMBERS, Integer.toString(result.dependencyMemberCount()));
        attributes.put(CommandAttributeKeys.MAIN_SOURCE_FILES, Integer.toString(result.mainSourceCount()));
        attributes.put(CommandAttributeKeys.TEST_SOURCE_FILES, Integer.toString(result.testSourceCount()));
        attributes.put(CommandAttributeKeys.MAIN_COMPILATIONS_SKIPPED, Integer.toString(result.mainCompilationSkippedCount()));
        attributes.put(CommandAttributeKeys.MAIN_COMPILATIONS_EXECUTED, Integer.toString(result.mainCompilationExecutedCount()));
        attributes.put(CommandAttributeKeys.TEST_COMPILATIONS_SKIPPED, Integer.toString(result.testCompilationSkippedCount()));
        attributes.put(CommandAttributeKeys.TEST_COMPILATIONS_EXECUTED, Integer.toString(result.testCompilationExecutedCount()));
        attributes.put(CommandAttributeKeys.TEST_RUNTIME_CLASSPATH_ENTRIES, Integer.toString(result.testRuntimeClasspathEntryCount()));
        attributes.put(CommandAttributeKeys.TEST_LAUNCHER_CLASSPATH_ENTRIES, Integer.toString(result.testLauncherClasspathEntryCount()));
        attributes.put(CommandAttributeKeys.TEST_DISCOVERY_SCAN_ROOTS, Integer.toString(result.testDiscoveryScanRootCount()));
        addTestWorkerAttributes(
                attributes,
                result.testWorkerStartCount(),
                result.testWorkerRequestCount());
        addMainFingerprintAttributes(
                attributes,
                result.mainFingerprintCheckNanos(),
                result.mainFingerprintWriteNanos());
        attributes.put(CommandAttributeKeys.TEST_FINGERPRINT_CHECK_MILLIS, Long.toString(result.testFingerprintCheckMillis()));
        attributes.put(CommandAttributeKeys.TEST_FINGERPRINT_CHECK_NANOS, Long.toString(result.testFingerprintCheckNanos()));
        attributes.put(CommandAttributeKeys.TEST_FINGERPRINT_WRITE_MILLIS, Long.toString(result.testFingerprintWriteMillis()));
        attributes.put(CommandAttributeKeys.TEST_FINGERPRINT_WRITE_NANOS, Long.toString(result.testFingerprintWriteNanos()));
        attributes.put(CommandAttributeKeys.TEST_CLASS_SELECTORS, Integer.toString(result.testClassSelectorCount()));
        attributes.put(CommandAttributeKeys.TEST_METHOD_SELECTORS, Integer.toString(result.testMethodSelectorCount()));
        attributes.put(CommandAttributeKeys.TEST_PATTERNS, Integer.toString(result.testPatternCount()));
        attributes.put(CommandAttributeKeys.TEST_INCLUDED_TAGS, Integer.toString(result.testIncludedTagCount()));
        attributes.put(CommandAttributeKeys.TEST_EXCLUDED_TAGS, Integer.toString(result.testExcludedTagCount()));
        addSlowTestEvidenceAttributes(attributes);
        attributes.put(CommandAttributeKeys.RESOLVED_LOCKFILE, Boolean.toString(result.resolvedLockfile()));
        addWorkspaceToolchainAttributes(attributes, result.toolchainMetrics());
        CommandJavacWorkerAttributes.add(attributes);
        return attributes;
    }

    public static Map<String, String> workspaceTestCompile(WorkspaceTestCompileResult result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(CommandAttributeKeys.MEMBERS, Integer.toString(result.members().size()));
        attributes.put(CommandAttributeKeys.INCLUDED_MEMBERS, Integer.toString(result.includedMemberCount()));
        attributes.put(CommandAttributeKeys.SELECTED_MEMBERS, Integer.toString(result.selectedMemberCount()));
        attributes.put(CommandAttributeKeys.DEPENDENCY_MEMBERS, Integer.toString(result.dependencyMemberCount()));
        attributes.put(CommandAttributeKeys.MAIN_SOURCE_FILES, Integer.toString(result.mainSourceCount()));
        attributes.put(CommandAttributeKeys.TEST_SOURCE_FILES, Integer.toString(result.testSourceCount()));
        attributes.put(
                CommandAttributeKeys.MAIN_COMPILATIONS_SKIPPED,
                Integer.toString(result.mainCompilationSkippedCount()));
        attributes.put(
                CommandAttributeKeys.MAIN_COMPILATIONS_EXECUTED,
                Integer.toString(result.mainCompilationExecutedCount()));
        attributes.put(
                CommandAttributeKeys.TEST_COMPILATIONS_SKIPPED,
                Integer.toString(result.testCompilationSkippedCount()));
        attributes.put(
                CommandAttributeKeys.TEST_COMPILATIONS_EXECUTED,
                Integer.toString(result.testCompilationExecutedCount()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_BUILD_MAX_WORKERS,
                Integer.toString(result.maxWorkers()));
        addMainFingerprintAttributes(
                attributes,
                result.mainFingerprintCheckNanos(),
                result.mainFingerprintWriteNanos());
        attributes.put(
                CommandAttributeKeys.TEST_FINGERPRINT_CHECK_MILLIS,
                Long.toString(result.testFingerprintCheckNanos() / 1_000_000L));
        attributes.put(
                CommandAttributeKeys.TEST_FINGERPRINT_CHECK_NANOS,
                Long.toString(result.testFingerprintCheckNanos()));
        attributes.put(
                CommandAttributeKeys.TEST_FINGERPRINT_WRITE_MILLIS,
                Long.toString(result.testFingerprintWriteNanos() / 1_000_000L));
        attributes.put(
                CommandAttributeKeys.TEST_FINGERPRINT_WRITE_NANOS,
                Long.toString(result.testFingerprintWriteNanos()));
        attributes.put(CommandAttributeKeys.RESOLVED_LOCKFILE, Boolean.toString(result.resolvedLockfile()));
        addWorkspaceToolchainAttributes(attributes, result.toolchainMetrics());
        return attributes;
    }

    private static void addWorkspaceToolchainAttributes(
            Map<String, String> attributes,
            sh.zolt.workspace.service.WorkspaceTestToolchainMetrics metrics) {
        attributes.put(
                CommandAttributeKeys.WORKSPACE_TOOLCHAIN_LOCKFILE_PARSES,
                Integer.toString(metrics.lockfileParses()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_TOOLCHAIN_IDENTITY_CALCULATIONS,
                Integer.toString(metrics.mainIdentityCalculations()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_TOOLCHAIN_IDENTITY_CACHE_HITS,
                Integer.toString(metrics.mainIdentityCacheHits()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_TEST_RUNTIME_TOOLCHAIN_IDENTITY_CALCULATIONS,
                Integer.toString(metrics.testRuntimeIdentityCalculations()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_TEST_RUNTIME_TOOLCHAIN_IDENTITY_CACHE_HITS,
                Integer.toString(metrics.testRuntimeIdentityCacheHits()));
    }

    private static void addTestRunnerTimingAttributes(Map<String, String> attributes, TestRunResult result) {
        if (result.testRunnerStartupNanos() >= 0L) {
            attributes.put(CommandAttributeKeys.TEST_RUNNER_STARTUP_MILLIS, Long.toString(result.testRunnerStartupNanos() / 1_000_000L));
            attributes.put(CommandAttributeKeys.TEST_RUNNER_STARTUP_NANOS, Long.toString(result.testRunnerStartupNanos()));
        }
        if (result.testRunnerRequestNanos() >= 0L) {
            attributes.put(CommandAttributeKeys.TEST_RUNNER_REQUEST_MILLIS, Long.toString(result.testRunnerRequestNanos() / 1_000_000L));
            attributes.put(CommandAttributeKeys.TEST_RUNNER_REQUEST_NANOS, Long.toString(result.testRunnerRequestNanos()));
        }
    }

    private static void addTestWorkerAttributes(
            Map<String, String> attributes,
            int starts,
            int requests) {
        attributes.put(
                CommandAttributeKeys.TEST_WORKER_STARTS,
                Integer.toString(starts));
        attributes.put(
                CommandAttributeKeys.TEST_WORKER_REQUESTS,
                Integer.toString(requests));
    }

    private static void addSlowTestEvidenceAttributes(Map<String, String> attributes) {
        attributes.put(CommandAttributeKeys.TEST_SLOW_ENTRIES, "0");
        attributes.put(CommandAttributeKeys.TEST_SLOW_EVIDENCE, "unavailable");
    }

    private static void addMainCompileDiagnostics(Map<String, String> attributes, CompileDiagnostics diagnostics) {
        addCompileDiagnostics(attributes, CommandAttributeKeys.MAIN_PREFIX, diagnostics);
    }

    private static void addTestCompileDiagnostics(Map<String, String> attributes, CompileDiagnostics diagnostics) {
        addCompileDiagnostics(attributes, CommandAttributeKeys.TEST_PREFIX, diagnostics);
    }

    private static void addCompileDiagnostics(
            Map<String, String> attributes,
            String prefix,
            CompileDiagnostics diagnostics) {
        CompileDiagnostics values = diagnostics == null ? CompileDiagnostics.empty() : diagnostics;
        attributes.put(prefix + CommandAttributeKeys.SOURCES_ADDED_SUFFIX, Integer.toString(values.sourcesAdded()));
        attributes.put(prefix + CommandAttributeKeys.SOURCES_CHANGED_SUFFIX, Integer.toString(values.sourcesChanged()));
        attributes.put(prefix + CommandAttributeKeys.SOURCES_DELETED_SUFFIX, Integer.toString(values.sourcesDeleted()));
        attributes.put(prefix + CommandAttributeKeys.SOURCES_RECOMPILED_SUFFIX, Integer.toString(values.sourcesRecompiled()));
        attributes.put(
                prefix + CommandAttributeKeys.DEPENDENT_SOURCES_RECOMPILED_SUFFIX,
                Integer.toString(values.dependentSourcesRecompiled()));
        attributes.put(prefix + CommandAttributeKeys.CLASSES_DELETED_SUFFIX, Integer.toString(values.classesDeleted()));
        attributes.put(prefix + CommandAttributeKeys.ABI_CHANGED_CLASSES_SUFFIX, Integer.toString(values.abiChangedClasses()));
        attributes.put(
                prefix + CommandAttributeKeys.PACKAGE_PRIVATE_ABI_CHANGED_CLASSES_SUFFIX,
                Integer.toString(values.packagePrivateAbiChangedClasses()));
    }

    private static void addMainFingerprintAttributes(Map<String, String> attributes, BuildResult result) {
        addMainFingerprintAttributes(
                attributes,
                result.mainFingerprintCheckNanos(),
                result.mainFingerprintWriteNanos());
    }

    private static void addMainFingerprintAttributes(
            Map<String, String> attributes,
            long checkNanos,
            long writeNanos) {
        attributes.put(CommandAttributeKeys.MAIN_FINGERPRINT_CHECK_MILLIS, Long.toString(checkNanos / 1_000_000L));
        attributes.put(CommandAttributeKeys.MAIN_FINGERPRINT_CHECK_NANOS, Long.toString(checkNanos));
        attributes.put(CommandAttributeKeys.MAIN_FINGERPRINT_WRITE_MILLIS, Long.toString(writeNanos / 1_000_000L));
        attributes.put(CommandAttributeKeys.MAIN_FINGERPRINT_WRITE_NANOS, Long.toString(writeNanos));
    }

    private static void addTestFingerprintAttributes(Map<String, String> attributes, TestCompileResult result) {
        attributes.put(CommandAttributeKeys.TEST_FINGERPRINT_CHECK_MILLIS, Long.toString(result.testFingerprintCheckMillis()));
        attributes.put(CommandAttributeKeys.TEST_FINGERPRINT_CHECK_NANOS, Long.toString(result.testFingerprintCheckNanos()));
        attributes.put(CommandAttributeKeys.TEST_FINGERPRINT_WRITE_MILLIS, Long.toString(result.testFingerprintWriteMillis()));
        attributes.put(CommandAttributeKeys.TEST_FINGERPRINT_WRITE_NANOS, Long.toString(result.testFingerprintWriteNanos()));
    }

    private static void addTestSelectionAttributes(Map<String, String> attributes, TestSelection selection) {
        attributes.put(CommandAttributeKeys.TEST_CLASS_SELECTORS, Integer.toString(selection.classSelectors().size()));
        attributes.put(CommandAttributeKeys.TEST_METHOD_SELECTORS, Integer.toString(selection.methodSelectors().size()));
        attributes.put(CommandAttributeKeys.TEST_PATTERNS, Integer.toString(selection.classNamePatterns().size()));
        attributes.put(CommandAttributeKeys.TEST_INCLUDED_TAGS, Integer.toString(selection.includedTags().size()));
        attributes.put(CommandAttributeKeys.TEST_EXCLUDED_TAGS, Integer.toString(selection.excludedTags().size()));
    }
}
