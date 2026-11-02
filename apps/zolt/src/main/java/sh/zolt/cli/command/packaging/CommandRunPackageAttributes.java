package sh.zolt.cli.command.packaging;

import sh.zolt.build.run.RunPackageResult;
import sh.zolt.cli.command.CommandAttributeKeys;
import sh.zolt.workspace.packaging.WorkspaceRunPackageResult;
import java.util.LinkedHashMap;
import java.util.Map;

final class CommandRunPackageAttributes {
    private CommandRunPackageAttributes() {
    }

    static Map<String, String> runPackage(RunPackageResult result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(CommandAttributeKeys.MODE, result.packageResult().mode().configValue());
        attributes.put(CommandAttributeKeys.ENTRIES, Integer.toString(result.packageResult().entryCount()));
        attributes.put(CommandAttributeKeys.HAS_MAIN_CLASS, Boolean.toString(result.packageResult().hasMainClass()));
        attributes.put(CommandAttributeKeys.MAIN_CLASS, result.javaRunResult().mainClass());
        attributes.put(CommandAttributeKeys.MAIN_COMPILATION_SKIPPED, Boolean.toString(result.packageResult().buildResult().mainCompilationSkipped()));
        attributes.put(CommandAttributeKeys.MAIN_COMPILATION_MODE, result.packageResult().buildResult().mainCompilationMode());
        attributes.put(
                CommandAttributeKeys.MAIN_INCREMENTAL_FALLBACK_REASON,
                result.packageResult().buildResult().mainIncrementalFallbackReason());
        attributes.put(CommandAttributeKeys.RESOLVED_LOCKFILE, Boolean.toString(result.packageResult().buildResult().resolvedLockfile()));
        attributes.put(CommandAttributeKeys.OUTPUT_BYTES, Integer.toString(result.javaRunResult().output().length()));
        return attributes;
    }

    static Map<String, String> workspaceRunPackage(WorkspaceRunPackageResult result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(CommandAttributeKeys.MEMBERS, Integer.toString(result.members().size()));
        attributes.put(CommandAttributeKeys.MAIN_SOURCE_FILES, Integer.toString(workspaceRunPackageSourceCount(result)));
        attributes.put(CommandAttributeKeys.MAIN_COMPILATIONS_SKIPPED, Integer.toString(workspaceRunPackageMainCompilationSkippedCount(result)));
        attributes.put(CommandAttributeKeys.MAIN_COMPILATIONS_RESTORED, Integer.toString(workspaceRunPackageMainCompilationRestoredCount(result)));
        attributes.put(CommandAttributeKeys.MAIN_COMPILATIONS_EXECUTED, Integer.toString(workspaceRunPackageMainCompilationExecutedCount(result)));
        attributes.put(CommandAttributeKeys.MAIN_RESTORED_CLASSES, Integer.toString(workspaceRunPackageMainRestoredClassCount(result)));
        attributes.put(CommandAttributeKeys.ENTRIES, Integer.toString(workspaceRunPackageEntryCount(result)));
        attributes.put(CommandAttributeKeys.RESOLVED_LOCKFILE, Boolean.toString(result.resolvedLockfile()));
        attributes.put(CommandAttributeKeys.OUTPUT_BYTES, Integer.toString(workspaceRunPackageOutputBytes(result)));
        return attributes;
    }

    private static int workspaceRunPackageSourceCount(WorkspaceRunPackageResult result) {
        return result.builtMembers().stream()
                .mapToInt(member -> member.result().sourceCount())
                .sum();
    }

    private static int workspaceRunPackageMainCompilationSkippedCount(WorkspaceRunPackageResult result) {
        return (int) result.builtMembers().stream()
                .filter(member -> member.result().mainCompilationSkipped())
                .count();
    }

    private static int workspaceRunPackageMainCompilationExecutedCount(WorkspaceRunPackageResult result) {
        return result.builtMembers().size()
                - workspaceRunPackageMainCompilationSkippedCount(result)
                - workspaceRunPackageMainCompilationRestoredCount(result);
    }

    private static int workspaceRunPackageMainCompilationRestoredCount(WorkspaceRunPackageResult result) {
        return (int) result.builtMembers().stream()
                .filter(member -> member.result().mainCompilationRestored())
                .count();
    }

    private static int workspaceRunPackageMainRestoredClassCount(WorkspaceRunPackageResult result) {
        return result.builtMembers().stream()
                .mapToInt(member -> member.result().mainRestoredClassCount())
                .sum();
    }

    private static int workspaceRunPackageEntryCount(WorkspaceRunPackageResult result) {
        return result.members().stream()
                .mapToInt(member -> member.result().packageResult().entryCount())
                .sum();
    }

    private static int workspaceRunPackageOutputBytes(WorkspaceRunPackageResult result) {
        return result.members().stream()
                .mapToInt(member -> member.result().javaRunResult().output().length())
                .sum();
    }
}
