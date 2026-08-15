package sh.zolt.workspace.service;

import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import java.util.List;

/**
 * Adds package metadata only for member features that consume resolved packages.
 */
final class WorkspaceBuildRequirementResolver {
    WorkspaceBuildRequirements forMember(
            WorkspaceBuildRequirements requested,
            ProjectConfig config) {
        boolean packageInputs = requested.packageInputs()
                || requiresPackageInputs(config.build().generatedMainSources())
                || (requested.testCompileClasspath()
                        && requiresPackageInputs(config.build().generatedTestSources()))
                || config.frameworkSettings().springBoot().nativeEnabled();
        return requested.withPackageInputs(packageInputs);
    }

    static boolean requiresPackageInputs(List<GeneratedSourceStep> steps) {
        return steps.stream().anyMatch(step ->
                step.kind() == GeneratedSourceKind.OPENAPI
                        || step.kind() == GeneratedSourceKind.EXEC);
    }
}
