package sh.zolt.build.generatedsource;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceStep;
import java.nio.file.Path;

/**
 * Classifies an exec step's build position. A step is <em>post-compile</em> when it uses the built-in
 * {@code project} pseudo-tool or declares an input under a configured compile output; such a step runs
 * after compilation. This predicate is the single source of truth shared by the scheduler (ordering),
 * the service (execution phase), and the module fingerprint (which must NOT hash post-compile outputs,
 * to avoid a compile-gates-its-own-input cycle).
 */
public final class ExecStepClassification {
    private ExecStepClassification() {
    }

    public static boolean isProjectRunner(GeneratedSourceStep step) {
        return "project".equals(step.exec().tool().runner());
    }

    public static boolean isPostCompile(GeneratedSourceStep step, Path projectRoot, BuildSettings build) {
        if (isProjectRunner(step)) {
            return true;
        }
        return step.inputs().stream()
                .anyMatch(input -> isCompileOutputInput(input, projectRoot, build));
    }

    /** Whether one declared exec input is rooted in a configured compiled output. */
    public static boolean isCompileOutputInput(String input, Path projectRoot, BuildSettings build) {
        Path base = projectRoot.resolve(ExecInputExpander.literalBase(input)).normalize();
        return base.startsWith(projectRoot.resolve(build.output()).normalize())
                || base.startsWith(projectRoot.resolve(build.testOutput()).normalize())
                || base.startsWith(projectRoot.resolve(build.integrationTestOutput()).normalize());
    }
}
