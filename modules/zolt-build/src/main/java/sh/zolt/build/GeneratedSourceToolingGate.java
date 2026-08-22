package sh.zolt.build;

import sh.zolt.lockfile.ProjectLockfile;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Decides whether a project declares OpenAPI or exec generated sources whose tooling is absent from
 * {@code zolt.lock}, so the build can trigger a re-resolve (online) or fail with an actionable error
 * (offline). Kept out of {@link BuildService} to keep that class within its file-size budget.
 */
final class GeneratedSourceToolingGate {
    private GeneratedSourceToolingGate() {
    }

    static boolean openApiToolingMissing(
            ZoltLockfileReader lockfileReader,
            Path projectDirectory,
            ProjectConfig config,
            boolean offline) {
        return toolingMissing(
                lockfileReader,
                projectDirectory,
                offline,
                hasKind(config, GeneratedSourceKind.OPENAPI),
                DependencyScope.TOOL_OPENAPI,
                "OpenAPI generation requires locked tool artifacts in scope `tool-openapi`, "
                        + "but zolt.lock does not contain them.",
                "Run `zolt resolve` without --offline to seed the OpenAPI generator tooling, then retry.");
    }

    static boolean execToolingMissing(
            ZoltLockfileReader lockfileReader,
            Path projectDirectory,
            ProjectConfig config,
            boolean offline) {
        return toolingMissing(
                lockfileReader,
                projectDirectory,
                offline,
                hasJvmExecStep(config),
                DependencyScope.TOOL_EXEC,
                "Exec generation requires locked tool artifacts in scope `tool-exec`, "
                        + "but zolt.lock does not contain them.",
                "Run `zolt resolve` without --offline to seed the exec tooling, then retry.");
    }

    private static boolean toolingMissing(
            ZoltLockfileReader lockfileReader,
            Path projectDirectory,
            boolean offline,
            boolean hasSteps,
            DependencyScope toolScope,
            String offlineSummary,
            String offlineRemediation) {
        if (!hasSteps) {
            return false;
        }
        Path lockfilePath = ProjectLockfile.in(projectDirectory);
        if (!Files.isRegularFile(lockfilePath)) {
            return false;
        }
        ZoltLockfile lockfile = lockfileReader.read(lockfilePath);
        boolean hasTool = lockfile.packages().stream()
                .anyMatch(lockPackage -> lockPackage.scope() == toolScope);
        if (hasTool) {
            return false;
        }
        if (offline) {
            throw BuildException.actionable(offlineSummary, offlineRemediation);
        }
        return true;
    }

    private static boolean hasKind(ProjectConfig config, GeneratedSourceKind kind) {
        return config.build().generatedMainSources().stream().anyMatch(step -> step.kind() == kind)
                || config.build().generatedTestSources().stream().anyMatch(step -> step.kind() == kind);
    }

    /** Only jvm-runner exec steps resolve into tool-exec; process/project steps need no locked tooling. */
    private static boolean hasJvmExecStep(ProjectConfig config) {
        return Stream.concat(
                        config.build().generatedMainSources().stream(),
                        config.build().generatedTestSources().stream())
                .anyMatch(step -> step.kind() == GeneratedSourceKind.EXEC
                        && "jvm".equals(step.exec().tool().runner()));
    }
}
