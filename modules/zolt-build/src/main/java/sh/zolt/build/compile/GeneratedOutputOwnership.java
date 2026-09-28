package sh.zolt.build.compile;

import sh.zolt.build.BuildException;
import sh.zolt.build.generatedsource.ExecInputExpander;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Distinguishes authored exec inputs from inputs owned by another same-scope exec step. */
final class GeneratedOutputOwnership {
    private GeneratedOutputOwnership() {
    }

    static Optional<ConfiguredPath> protectedInput(
            Path root,
            String scope,
            GeneratedSourceStep consumer,
            String input,
            List<GeneratedSourceStep> steps,
            ConfiguredPath configuredInput) {
        if (producedByAnotherStep(root, scope, consumer, input, steps)) {
            return Optional.empty();
        }
        Path protectedPath = consumer.kind() == GeneratedSourceKind.EXEC
                ? root.resolve(ExecInputExpander.literalBase(input)).normalize()
                : configuredInput.path();
        return Optional.of(new ConfiguredPath(
                configuredInput.key(), configuredInput.configured(), protectedPath, false));
    }

    private static boolean producedByAnotherStep(
            Path root,
            String scope,
            GeneratedSourceStep consumer,
            String input,
            List<GeneratedSourceStep> steps) {
        if (consumer.kind() != GeneratedSourceKind.EXEC) {
            return false;
        }
        Path inputBase = root.resolve(ExecInputExpander.literalBase(input)).normalize();
        for (GeneratedSourceStep producer : steps) {
            if (producer.id().equals(consumer.id())
                    || producer.kind() != GeneratedSourceKind.EXEC) {
                continue;
            }
            Path output = output(root, scope, producer);
            if (inputBase.startsWith(output)) {
                return true;
            }
        }
        return false;
    }

    private static Path output(Path root, String scope, GeneratedSourceStep step) {
        String key = "[generated." + scope + "." + step.id() + "].output";
        try {
            return ProjectPaths.output(root, key, step.output()).toAbsolutePath().normalize();
        } catch (ProjectPathException exception) {
            throw new BuildException(exception.getMessage(), exception);
        }
    }
}
