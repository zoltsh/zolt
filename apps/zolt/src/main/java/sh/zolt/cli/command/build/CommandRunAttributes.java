package sh.zolt.cli.command.build;

import sh.zolt.build.CompileDiagnostics;
import sh.zolt.build.run.RunResult;
import sh.zolt.cli.command.CommandAttributeKeys;
import sh.zolt.workspace.run.WorkspaceRunResult;
import java.util.LinkedHashMap;
import java.util.Map;

final class CommandRunAttributes {
    private CommandRunAttributes() {
    }

    static Map<String, String> run(RunResult result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(CommandAttributeKeys.MAIN_CLASS, result.javaRunResult().mainClass());
        attributes.put(CommandAttributeKeys.MAIN_SOURCE_FILES, Integer.toString(result.buildResult().sourceCount()));
        attributes.put(CommandAttributeKeys.RESOURCE_FILES, Integer.toString(result.buildResult().resourceCount()));
        attributes.put(CommandAttributeKeys.MAIN_COMPILATION_SKIPPED, Boolean.toString(result.buildResult().mainCompilationSkipped()));
        attributes.put(CommandAttributeKeys.MAIN_COMPILATION_MODE, result.buildResult().mainCompilationMode());
        attributes.put(CommandAttributeKeys.MAIN_INCREMENTAL_FALLBACK_REASON, result.buildResult().mainIncrementalFallbackReason());
        addMainCompileDiagnostics(attributes, result.buildResult().mainCompileDiagnostics());
        attributes.put(CommandAttributeKeys.RESOLVED_LOCKFILE, Boolean.toString(result.buildResult().resolvedLockfile()));
        attributes.put(CommandAttributeKeys.OUTPUT_BYTES, Integer.toString(result.javaRunResult().output().length()));
        return attributes;
    }

    static Map<String, String> workspaceRun(WorkspaceRunResult result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(CommandAttributeKeys.MEMBERS, Integer.toString(result.members().size()));
        attributes.put(CommandAttributeKeys.MAIN_SOURCE_FILES, Integer.toString(workspaceRunSourceCount(result)));
        attributes.put(CommandAttributeKeys.MAIN_COMPILATIONS_SKIPPED, Integer.toString(workspaceRunMainCompilationSkippedCount(result)));
        attributes.put(CommandAttributeKeys.MAIN_COMPILATIONS_RESTORED, Integer.toString(workspaceRunMainCompilationRestoredCount(result)));
        attributes.put(CommandAttributeKeys.MAIN_COMPILATIONS_EXECUTED, Integer.toString(workspaceRunMainCompilationExecutedCount(result)));
        attributes.put(CommandAttributeKeys.MAIN_RESTORED_CLASSES, Integer.toString(workspaceRunMainRestoredClassCount(result)));
        attributes.put(CommandAttributeKeys.RESOLVED_LOCKFILE, Boolean.toString(result.resolvedLockfile()));
        attributes.put(CommandAttributeKeys.OUTPUT_BYTES, Integer.toString(workspaceRunOutputBytes(result)));
        return attributes;
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

    private static int workspaceRunSourceCount(WorkspaceRunResult result) {
        return result.builtMembers().stream()
                .mapToInt(member -> member.result().sourceCount())
                .sum();
    }

    private static int workspaceRunMainCompilationSkippedCount(WorkspaceRunResult result) {
        return (int) result.builtMembers().stream()
                .filter(member -> member.result().mainCompilationSkipped())
                .count();
    }

    private static int workspaceRunMainCompilationExecutedCount(WorkspaceRunResult result) {
        return result.builtMembers().size()
                - workspaceRunMainCompilationSkippedCount(result)
                - workspaceRunMainCompilationRestoredCount(result);
    }

    private static int workspaceRunMainCompilationRestoredCount(WorkspaceRunResult result) {
        return (int) result.builtMembers().stream()
                .filter(member -> member.result().mainCompilationRestored())
                .count();
    }

    private static int workspaceRunMainRestoredClassCount(WorkspaceRunResult result) {
        return result.builtMembers().stream()
                .mapToInt(member -> member.result().mainRestoredClassCount())
                .sum();
    }

    private static int workspaceRunOutputBytes(WorkspaceRunResult result) {
        return result.members().stream()
                .mapToInt(member -> member.result().javaRunResult().output().length())
                .sum();
    }
}
