package sh.zolt.build.generatedsource.ksp;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import sh.zolt.build.BuildException;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import sh.zolt.project.ProtobufGenerationSettings;

/** Validates the fail-closed contract for one internally modeled KSP generation step. */
final class KspGeneratedSourceValidator {
    private KspGeneratedSourceValidator() {
    }

    static KspOutputLayout validate(
            Path projectRoot,
            String scope,
            GeneratedSourceStep step) {
        String subject = "[generated." + scope + "." + step.id() + "]";
        if (step.kind() != GeneratedSourceKind.KSP) {
            throw invalid(subject, "uses kind `" + step.kind().configValue() + "` instead of `ksp`");
        }
        if (!"kotlin".equals(step.language())) {
            throw invalid(subject, "must use the internal Kotlin source lane");
        }
        if (!step.inputs().isEmpty()) {
            throw invalid(subject, "must not declare inputs; KSP consumes the configured compile source roots");
        }
        if (!step.clean()) {
            throw invalid(subject, "must clean its owned output before each non-incremental KSP run");
        }
        if (!step.openApi().equals(OpenApiGenerationSettings.empty())
                || !step.protobuf().equals(ProtobufGenerationSettings.empty())
                || !step.exec().equals(ExecGenerationSettings.empty())) {
            throw invalid(subject, "contains settings owned by a different generated source kind");
        }
        KspOutputLayout layout = KspOutputLayout.resolve(projectRoot, scope, step);
        validateExistingOutputs(projectRoot, subject, step.output(), layout);
        return layout;
    }

    private static void validateExistingOutputs(
            Path projectRoot,
            String subject,
            String configuredBase,
            KspOutputLayout layout) {
        List<OwnedLane> lanes = List.of(
                new OwnedLane("output", layout.baseDirectory(), configuredBase),
                new OwnedLane("cache", layout.cachesDirectory(), configuredBase + "/cache"),
                new OwnedLane("classes", layout.classOutputDirectory(), configuredBase + "/classes"),
                new OwnedLane("kotlin", layout.kotlinOutputDirectory(), configuredBase + "/kotlin"),
                new OwnedLane("java", layout.javaOutputDirectory(), configuredBase + "/java"),
                new OwnedLane("resources", layout.resourceOutputDirectory(), configuredBase + "/resources"));
        for (OwnedLane lane : lanes) {
            if (!Files.exists(lane.path(), LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                ProjectPaths.requireExistingInsideProject(
                        projectRoot,
                        subject + "." + lane.name(),
                        lane.configured(),
                        lane.path());
            } catch (ProjectPathException exception) {
                throw new BuildException(
                        "Invalid KSP owned " + lane.name() + " path for " + subject + ". "
                                + exception.getMessage(),
                        exception);
            }
        }
    }

    private static BuildException invalid(String subject, String reason) {
        return BuildException.actionable(
                "KSP generated source step " + subject + " " + reason + ".",
                "Regenerate the KSP step from a supported Zolt manifest before retrying.");
    }

    private record OwnedLane(String name, Path path, String configured) {
    }
}
