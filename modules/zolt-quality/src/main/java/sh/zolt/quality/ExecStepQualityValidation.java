package sh.zolt.quality;

import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProducesLane;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Static structural validation for {@code kind = "exec"} steps in {@code zolt check}: runner-specific
 * tool sanity (jvm coordinates + mainClass, the process allowUnpinnedTool gate, the project mainClass),
 * lane/scope consistency, and the post-compile "cannot produce sources" cycle guard. Factored out of
 * {@link GeneratedSourceQualityCheck} to keep that class within its file-size budget.
 */
final class ExecStepQualityValidation {
    private ExecStepQualityValidation() {
    }

    static Optional<QualityCheckResult> invalid(
            Optional<String> member,
            Path projectRoot,
            String outputRoot,
            String scope,
            GeneratedSourceStep step) {
        String subject = "[generated." + scope + "." + step.id() + "]";
        Optional<QualityCheckResult> invalidTool = invalidTool(member, subject, step);
        if (invalidTool.isPresent()) {
            return invalidTool;
        }
        Optional<QualityCheckResult> invalidLane = invalidLane(member, subject, scope, step);
        if (invalidLane.isPresent()) {
            return invalidLane;
        }
        if (isPostCompile(step, projectRoot, outputRoot)
                && (step.exec().produces() == ProducesLane.JAVA_SOURCES
                        || step.exec().produces() == ProducesLane.TEST_SOURCES)) {
            return Optional.of(QualityCheckResult.failed(
                    QualityCheckService.GENERATED_SOURCES,
                    member,
                    subject,
                    "Exec step runs after compilation but produces " + step.exec().produces().configValue()
                            + ", which would feed that same compile.",
                    "Post-compile steps may only produce resources, test-resources, or intermediate."));
        }
        return Optional.empty();
    }

    private static Optional<QualityCheckResult> invalidTool(
            Optional<String> member, String subject, GeneratedSourceStep step) {
        String runner = step.exec().tool().runner();
        String toolName = step.exec().toolName();
        boolean resolvable = switch (runner) {
            case "jvm" -> !step.exec().tool().mainClass().isBlank() && !step.exec().tool().coordinates().isEmpty();
            case "process" -> !step.exec().tool().binary().isBlank() && !step.exec().tool().versionCommand().isEmpty();
            case "project" -> !step.exec().tool().mainClass().isBlank();
            default -> false;
        };
        if (!resolvable) {
            return Optional.of(QualityCheckResult.failed(
                    QualityCheckService.GENERATED_SOURCES,
                    member,
                    subject,
                    "Exec step references tool `" + toolName + "` (runner `" + runner + "`) that is not resolvable.",
                    "Declare the tool with a supported runner (jvm/process) or use tool = \"project\" with a mainClass."));
        }
        // Kept as defense in depth: running unpinned PATH bytes must fail closed even if the
        // parse-time acknowledgement in AuthoredGeneratedTool regresses.
        if ("process".equals(runner) && !step.exec().tool().allowUnpinnedTool()) {
            return Optional.of(QualityCheckResult.failed(
                    QualityCheckService.GENERATED_SOURCES,
                    member,
                    subject,
                    "Exec step runs unpinned PATH binary `" + step.exec().tool().binary()
                            + "` without acknowledging that its bytes are unprovable.",
                    "Set allowUnpinnedTool = true on [generated.tools." + toolName + "]."));
        }
        return Optional.empty();
    }

    private static Optional<QualityCheckResult> invalidLane(
            Optional<String> member, String subject, String scope, GeneratedSourceStep step) {
        ProducesLane produces = step.exec().produces();
        if ("main".equals(scope)) {
            if (produces == ProducesLane.TEST_SOURCES) {
                return Optional.of(laneFailure(member, subject, "test-sources",
                        "[generated.test." + step.id() + "]", "java-sources"));
            }
            if (produces == ProducesLane.TEST_RESOURCES) {
                return Optional.of(laneFailure(member, subject, "test-resources",
                        "[generated.test." + step.id() + "]", "resources"));
            }
        }
        if ("test".equals(scope)) {
            if (produces == ProducesLane.JAVA_SOURCES) {
                return Optional.of(laneFailure(member, subject, "java-sources",
                        "[generated.main." + step.id() + "]", "test-sources"));
            }
            if (produces == ProducesLane.RESOURCES) {
                return Optional.of(laneFailure(member, subject, "resources",
                        "[generated.main." + step.id() + "]", "test-resources"));
            }
        }
        return Optional.empty();
    }

    private static QualityCheckResult laneFailure(
            Optional<String> member, String subject, String lane, String otherSection, String otherLane) {
        return QualityCheckResult.failed(
                QualityCheckService.GENERATED_SOURCES,
                member,
                subject,
                "Exec step produces " + lane + " but is declared in the wrong lane.",
                "Move it to " + otherSection + " or set produces = \"" + otherLane + "\".");
    }

    private static boolean isPostCompile(GeneratedSourceStep step, Path projectRoot, String outputRoot) {
        if ("project".equals(step.exec().tool().runner())) {
            return true;
        }
        Path classes = projectRoot.resolve(outputRoot).resolve("classes").normalize();
        Path testClasses = projectRoot.resolve(outputRoot).resolve("test-classes").normalize();
        for (String input : step.inputs()) {
            Path base = projectRoot.resolve(literalBase(input)).normalize();
            if (base.startsWith(classes) || base.startsWith(testClasses)) {
                return true;
            }
        }
        return false;
    }

    private static String literalBase(String input) {
        int glob = -1;
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (character == '*' || character == '?' || character == '[') {
                glob = index;
                break;
            }
        }
        if (glob < 0) {
            return input;
        }
        String prefix = input.substring(0, glob);
        int slash = prefix.lastIndexOf('/');
        return slash < 0 ? "" : prefix.substring(0, slash);
    }
}
