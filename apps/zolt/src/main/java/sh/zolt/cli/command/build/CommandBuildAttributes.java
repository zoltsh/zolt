package sh.zolt.cli.command.build;

import sh.zolt.build.BuildResult;
import sh.zolt.build.CompileDiagnostics;
import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.cli.command.CommandAttributeKeys;
import sh.zolt.cli.command.CommandJavacWorkerAttributes;
import sh.zolt.framework.FrameworkBuildAugmentationResult;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceSelection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class CommandBuildAttributes {
    private CommandBuildAttributes() {
    }

    public static Map<String, String> build(BuildResult result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(CommandAttributeKeys.SOURCE_FILES, Integer.toString(result.sourceCount()));
        attributes.put(CommandAttributeKeys.RESOURCE_FILES, Integer.toString(result.resourceCount()));
        attributes.put(CommandAttributeKeys.RESOLVED_LOCKFILE, Boolean.toString(result.resolvedLockfile()));
        attributes.put(CommandAttributeKeys.MAIN_COMPILATION_SKIPPED, Boolean.toString(result.mainCompilationSkipped()));
        attributes.put(CommandAttributeKeys.MAIN_COMPILATION_MODE, result.mainCompilationMode());
        attributes.put(CommandAttributeKeys.MAIN_BUILD_CACHE_OUTCOME, result.mainBuildCacheOutcome());
        attributes.put(CommandAttributeKeys.MAIN_RESTORED_CLASSES, Integer.toString(result.mainRestoredClassCount()));
        attributes.put(CommandAttributeKeys.MAIN_INCREMENTAL_FALLBACK_REASON, result.mainIncrementalFallbackReason());
        addMainCompileDiagnostics(attributes, result.mainCompileDiagnostics());
        addMainFingerprintAttributes(attributes, result);
        return attributes;
    }

    public static Map<String, String> workspaceBuild(WorkspaceBuildResult result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(CommandAttributeKeys.MEMBERS, Integer.toString(result.members().size()));
        attributes.put(CommandAttributeKeys.SOURCE_FILES, Integer.toString(result.sourceCount()));
        attributes.put(CommandAttributeKeys.WORKSPACE_BUILD_WAVES, Integer.toString(result.buildWaveCount()));
        attributes.put(CommandAttributeKeys.WORKSPACE_BUILD_MAX_WORKERS, Integer.toString(result.buildMaxWorkers()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_GRAPH_CONSTRUCTION_NANOS,
                Long.toString(result.executionMetrics().graphConstructionNanos()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_CLASSPATH_CALCULATION_NANOS,
                Long.toString(result.executionMetrics().classpathCalculationNanos()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_PACKAGE_CALCULATION_NANOS,
                Long.toString(result.executionMetrics().packageCalculationNanos()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_MEMBER_EXECUTION_NANOS,
                Long.toString(result.executionMetrics().memberExecutionNanos()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_SCHEDULER_IDLE_NANOS,
                Long.toString(result.executionMetrics().schedulerIdleNanos()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_READY_QUEUE_PEAK,
                Integer.toString(result.executionMetrics().readyQueuePeak()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_FILE_SNAPSHOT_NANOS,
                Long.toString(result.executionMetrics().fileSnapshotNanos()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_BYTES_HASHED,
                Long.toString(result.executionMetrics().bytesHashed()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_FILES_HASHED,
                Integer.toString(result.executionMetrics().filesHashed()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_FILES_STATTED,
                Integer.toString(result.executionMetrics().filesStatted()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_FILES_REUSED,
                Integer.toString(result.executionMetrics().filesReused()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_MEMBERS_CONSIDERED,
                Integer.toString(result.executionMetrics().membersConsidered()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_MEMBERS_DECLARED_CLEAN,
                Integer.toString(result.executionMetrics().membersDeclaredClean()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_MEMBER_PIPELINE_INVOCATIONS,
                Integer.toString(result.executionMetrics().memberPipelineInvocations()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_MEMBERS_ADMITTED,
                Integer.toString(result.executionMetrics().membersAdmitted()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_MEMBERS_FINALIZED,
                Integer.toString(result.executionMetrics().membersFinalized()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_RUNTIME_CLASSPATH_CALCULATIONS,
                Integer.toString(result.executionMetrics().runtimeClasspathCalculations()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_TEST_CLASSPATH_CALCULATIONS,
                Integer.toString(result.executionMetrics().testClasspathCalculations()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_ABI_STATE_READS,
                Integer.toString(result.executionMetrics().abiStateReads()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_ABI_STATE_CACHE_HITS,
                Integer.toString(result.executionMetrics().abiStateCacheHits()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_TOOLCHAIN_RESOLUTIONS,
                Integer.toString(result.executionMetrics().toolchainResolutions()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_TOOLCHAIN_CACHE_HITS,
                Integer.toString(result.executionMetrics().toolchainCacheHits()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_TOOLCHAIN_LOCKFILE_PARSES,
                Integer.toString(result.executionMetrics().toolchainLockfileParses()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_TOOLCHAIN_IDENTITY_CALCULATIONS,
                Integer.toString(result.executionMetrics().toolchainIdentityCalculations()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_TOOLCHAIN_IDENTITY_CACHE_HITS,
                Integer.toString(result.executionMetrics().toolchainIdentityCacheHits()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_CLASSPATH_CALCULATIONS,
                Integer.toString(result.executionMetrics().classpathCalculations()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_PACKAGE_CALCULATIONS,
                Integer.toString(result.executionMetrics().packageCalculations()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_CLASSPATH_CACHE_HITS,
                Integer.toString(result.executionMetrics().classpathCacheHits()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_PACKAGE_CACHE_HITS,
                Integer.toString(result.executionMetrics().packageCacheHits()));
        addArtifactIntegrityAttributes(attributes, result.executionMetrics().artifactIntegrity());
        attributes.put(CommandAttributeKeys.MAIN_COMPILATIONS_SKIPPED, Integer.toString(result.mainCompilationSkippedCount()));
        attributes.put(CommandAttributeKeys.MAIN_COMPILATIONS_EXECUTED, Integer.toString(result.mainCompilationExecutedCount()));
        addMainCompileDiagnostics(attributes, result.mainCompileDiagnostics());
        attributes.put(CommandAttributeKeys.WORKSPACE_ABI_INVALIDATIONS, Integer.toString(result.workspaceAbiInvalidationCount()));
        attributes.put(CommandAttributeKeys.RESOLVED_LOCKFILE, Boolean.toString(result.resolvedLockfile()));
        addMainFingerprintAttributes(
                attributes,
                result.mainFingerprintCheckNanos(),
                result.mainFingerprintWriteNanos());
        CommandJavacWorkerAttributes.add(attributes);
        return attributes;
    }

    public static Map<String, String> workspaceBuild(WorkspaceBuildResult result, WorkspaceSelection selection) {
        Map<String, String> attributes = workspaceBuild(result);
        attributes.put(CommandAttributeKeys.INCLUDED_MEMBERS, Integer.toString(selection.includedMembers().size()));
        attributes.put(CommandAttributeKeys.SELECTED_MEMBERS, Integer.toString(selection.selectedMembers().size()));
        attributes.put(CommandAttributeKeys.DEPENDENCY_MEMBERS, Integer.toString(selection.includedMembers().size() - selection.selectedMembers().size()));
        return attributes;
    }

    public static Map<String, String> workspaceBuildPlan(WorkspaceBuildPlan plan) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(
                CommandAttributeKeys.INCLUDED_MEMBERS,
                Integer.toString(plan.selection().includedMembers().size()));
        attributes.put(
                CommandAttributeKeys.SELECTED_MEMBERS,
                Integer.toString(plan.selection().selectedMembers().size()));
        attributes.put(CommandAttributeKeys.RESOLVED_LOCKFILE, Boolean.toString(plan.resolvedLockfile()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_DISCOVERY_NANOS,
                Long.toString(plan.metrics().discoveryNanos()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_SELECTION_NANOS,
                Long.toString(plan.metrics().selectionNanos()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_RESOLUTION_NANOS,
                Long.toString(plan.metrics().resolutionNanos()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_LOCKFILE_READ_NANOS,
                Long.toString(plan.metrics().lockfileReadNanos()));
        attributes.put(
                CommandAttributeKeys.MEMBERS,
                Integer.toString(plan.metrics().workspaceMembers()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_EDGES,
                Integer.toString(plan.metrics().workspaceEdges()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_LOCKFILE_PACKAGES,
                Integer.toString(plan.metrics().lockfilePackages()));
        return attributes;
    }

    public static Map<String, String> frameworkAugmentation(Optional<FrameworkBuildAugmentationResult> result) {
        if (result.isEmpty()) {
            return Map.of(CommandAttributeKeys.ENABLED, "false");
        }
        FrameworkBuildAugmentationResult augmentation = result.orElseThrow();
        return Map.of(
                CommandAttributeKeys.ENABLED, "true",
                CommandAttributeKeys.RUNNER_JAR, augmentation.runnerJar().toString());
    }

    /**
     * Publishes what the command's shared artifact index actually did. {@code hashes} is the number
     * of cached artifacts read; {@code cacheHits} is the number of further requests for a path
     * already hashed — before the index existed each of those was another full re-read.
     */
    private static void addArtifactIntegrityAttributes(
            Map<String, String> attributes,
            VerifiedArtifactIndex.Metrics metrics) {
        attributes.put(CommandAttributeKeys.ARTIFACT_INTEGRITY_PATHS, Integer.toString(metrics.paths()));
        attributes.put(CommandAttributeKeys.ARTIFACT_INTEGRITY_BYTES, Long.toString(metrics.bytes()));
        attributes.put(CommandAttributeKeys.ARTIFACT_INTEGRITY_HASHES, Integer.toString(metrics.hashes()));
        attributes.put(CommandAttributeKeys.ARTIFACT_INTEGRITY_CACHE_HITS, Integer.toString(metrics.cacheHits()));
        attributes.put(CommandAttributeKeys.ARTIFACT_INTEGRITY_NANOS, Long.toString(metrics.nanos()));
    }

    private static void addMainCompileDiagnostics(Map<String, String> attributes, CompileDiagnostics diagnostics) {
        CompileDiagnostics values = diagnostics == null ? CompileDiagnostics.empty() : diagnostics;
        attributes.put(CommandAttributeKeys.MAIN_PREFIX + CommandAttributeKeys.SOURCES_ADDED_SUFFIX, Integer.toString(values.sourcesAdded()));
        attributes.put(CommandAttributeKeys.MAIN_PREFIX + CommandAttributeKeys.SOURCES_CHANGED_SUFFIX, Integer.toString(values.sourcesChanged()));
        attributes.put(CommandAttributeKeys.MAIN_PREFIX + CommandAttributeKeys.SOURCES_DELETED_SUFFIX, Integer.toString(values.sourcesDeleted()));
        attributes.put(CommandAttributeKeys.MAIN_PREFIX + CommandAttributeKeys.SOURCES_RECOMPILED_SUFFIX, Integer.toString(values.sourcesRecompiled()));
        attributes.put(
                CommandAttributeKeys.MAIN_PREFIX + CommandAttributeKeys.DEPENDENT_SOURCES_RECOMPILED_SUFFIX,
                Integer.toString(values.dependentSourcesRecompiled()));
        attributes.put(CommandAttributeKeys.MAIN_PREFIX + CommandAttributeKeys.CLASSES_DELETED_SUFFIX, Integer.toString(values.classesDeleted()));
        attributes.put(CommandAttributeKeys.MAIN_PREFIX + CommandAttributeKeys.ABI_CHANGED_CLASSES_SUFFIX, Integer.toString(values.abiChangedClasses()));
        attributes.put(
                CommandAttributeKeys.MAIN_PREFIX + CommandAttributeKeys.PACKAGE_PRIVATE_ABI_CHANGED_CLASSES_SUFFIX,
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
}
