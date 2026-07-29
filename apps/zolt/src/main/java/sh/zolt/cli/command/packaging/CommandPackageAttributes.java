package sh.zolt.cli.command.packaging;

import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.cli.command.CommandAttributeKeys;
import sh.zolt.workspace.packaging.WorkspacePackageResult;
import java.util.LinkedHashMap;
import java.util.Map;

final class CommandPackageAttributes {
    private CommandPackageAttributes() {
    }

    static Map<String, String> packageResult(PackageResult result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(CommandAttributeKeys.MODE, result.mode().configValue());
        attributes.put(CommandAttributeKeys.ENTRIES, Integer.toString(result.entryCount()));
        attributes.put(CommandAttributeKeys.HAS_MAIN_CLASS, Boolean.toString(result.hasMainClass()));
        attributes.put(CommandAttributeKeys.PACKAGE_REUSED, Boolean.toString(result.packagingReused()));
        attributes.put(
                CommandAttributeKeys.RESOLVED_LOCKFILE,
                Boolean.toString(result.buildResult().resolvedLockfile()));
        return attributes;
    }

    static Map<String, String> workspacePackage(WorkspacePackageResult result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(CommandAttributeKeys.MEMBERS, Integer.toString(result.members().size()));
        attributes.put(CommandAttributeKeys.ENTRIES, Integer.toString(result.entryCount()));
        attributes.put(CommandAttributeKeys.PACKAGES_EXECUTED, Integer.toString(result.packagedCount()));
        attributes.put(CommandAttributeKeys.PACKAGES_REUSED, Integer.toString(result.reusedCount()));
        attributes.put(
                CommandAttributeKeys.WORKSPACE_PACKAGE_MAX_WORKERS,
                Integer.toString(result.maxWorkers()));
        attributes.put(
                CommandAttributeKeys.RESOLVED_LOCKFILE,
                Boolean.toString(result.resolvedLockfile()));
        return attributes;
    }

    static Map<String, String> packagePlan(PackagePlan plan) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(CommandAttributeKeys.MODE, plan.mode().configValue());
        attributes.put(CommandAttributeKeys.DEPENDENCIES, String.valueOf(plan.dependencies().size()));
        attributes.put(CommandAttributeKeys.WARNINGS, String.valueOf(plan.warnings().size()));
        return attributes;
    }
}
