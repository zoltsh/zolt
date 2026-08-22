package sh.zolt.build.generatedsource;

import sh.zolt.build.BuildException;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProducesLane;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Validates a single exec step just before it runs: runner-specific tool sanity (jvm coordinates +
 * mainClass, the process PATH gate, the project pseudo-tool mainClass), lane/scope consistency, the
 * post-compile "cannot produce sources" cycle guard, and real-path-contained, existing inputs.
 */
final class ExecGeneratedSourceValidator {
    private ExecGeneratedSourceValidator() {
    }

    static void validateStep(Path projectRoot, String outputRoot, String scope, GeneratedSourceStep step) {
        ExecGenerationSettings exec = step.exec();
        String subject = "[generated." + scope + "." + step.id() + "]";
        validateTool(exec, subject);
        validateLane(scope, step, subject);
        validatePostCompileLane(projectRoot, outputRoot, scope, step, subject);
        validateInputs(projectRoot, scope, step, subject);
    }

    private static void validateTool(ExecGenerationSettings exec, String subject) {
        String runner = exec.tool().runner();
        switch (runner) {
            case "jvm" -> {
                if (exec.tool().mainClass().isBlank()) {
                    throw BuildException.actionable(
                            "Exec tool `" + exec.toolName() + "` for " + subject + " has no mainClass.",
                            "Add mainClass to [generated.tools." + exec.toolName() + "].");
                }
                if (exec.tool().coordinates().isEmpty()) {
                    throw BuildException.actionable(
                            "Exec tool `" + exec.toolName() + "` for " + subject + " declares no coordinates.",
                            "Add at least one { coordinate, version|versionRef } to [generated.tools."
                                    + exec.toolName() + "].");
                }
            }
            case "process" -> {
                if (exec.tool().binary().isBlank() || exec.tool().versionCommand().isEmpty()) {
                    throw BuildException.actionable(
                            "Exec tool `" + exec.toolName() + "` for " + subject + " is missing binary or versionCommand.",
                            "Add binary and versionCommand to [generated.tools." + exec.toolName() + "].");
                }
                if (!exec.tool().allowUnpinnedTool()) {
                    throw BuildException.actionable(
                            "Exec tool `" + exec.toolName() + "` for " + subject
                                    + "` runs an unpinned PATH binary `" + exec.tool().binary()
                                    + "` whose bytes Zolt cannot lock.",
                            "Set allowUnpinnedTool = true on [generated.tools." + exec.toolName()
                                    + "] to acknowledge that PATH tool identity rests on the probed version only.");
                }
            }
            case "project" -> {
                if (exec.tool().mainClass().isBlank()) {
                    throw BuildException.actionable(
                            "Exec step " + subject + " uses tool = \"project\" without a mainClass.",
                            "Add mainClass to " + subject + " naming the class to run on the member's classpath.");
                }
            }
            default -> throw BuildException.actionable(
                    "Exec step " + subject + " uses unsupported runner `" + runner + "`.",
                    "Use runner = \"jvm\" or \"process\", or tool = \"project\".");
        }
    }

    private static void validateLane(String scope, GeneratedSourceStep step, String subject) {
        ProducesLane produces = step.exec().produces();
        if ("main".equals(scope)) {
            if (produces == ProducesLane.TEST_SOURCES) {
                throw laneError(subject, "test-sources", "[generated.test." + step.id() + "]", "java-sources");
            }
            if (produces == ProducesLane.TEST_RESOURCES) {
                throw laneError(subject, "test-resources", "[generated.test." + step.id() + "]", "resources");
            }
        }
        if ("test".equals(scope)) {
            if (produces == ProducesLane.JAVA_SOURCES) {
                throw laneError(subject, "java-sources", "[generated.main." + step.id() + "]", "test-sources");
            }
            if (produces == ProducesLane.RESOURCES) {
                throw laneError(subject, "resources", "[generated.main." + step.id() + "]", "test-resources");
            }
        }
    }

    private static BuildException laneError(String subject, String lane, String otherSection, String otherLane) {
        return BuildException.actionable(
                "Exec step " + subject + " produces " + lane + " but is declared in the wrong lane.",
                "Move it to " + otherSection + " or set produces = \"" + otherLane + "\".");
    }

    private static void validatePostCompileLane(
            Path projectRoot, String outputRoot, String scope, GeneratedSourceStep step, String subject) {
        if (!ExecStepClassification.isPostCompile(step, projectRoot, outputRoot)) {
            return;
        }
        ProducesLane produces = step.exec().produces();
        if (produces == ProducesLane.JAVA_SOURCES || produces == ProducesLane.TEST_SOURCES) {
            throw BuildException.actionable(
                    "Exec step " + subject + " runs after compilation (project runner or an input under compiled "
                            + "classes) but produces " + produces.configValue() + ", which would feed that same compile.",
                    "Post-compile steps may only produce resources, test-resources, or intermediate.");
        }
    }

    private static void validateInputs(Path projectRoot, String scope, GeneratedSourceStep step, String subject) {
        for (String input : step.inputs()) {
            if (ExecInputExpander.isGlob(input)) {
                continue;
            }
            Path path = ExecGeneratedSourcePaths.inputPath(projectRoot, input, scope, step.id(), "inputs");
            if (!Files.exists(path)) {
                throw BuildException.actionable(
                        "Exec step " + subject + " input `" + input + "` does not exist.",
                        "Create the input or fix the path in " + subject + ".inputs.");
            }
        }
    }
}
