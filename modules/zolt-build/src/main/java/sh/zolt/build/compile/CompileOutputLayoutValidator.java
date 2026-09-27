package sh.zolt.build.compile;

import sh.zolt.build.BuildException;
import sh.zolt.build.generatedsource.ExecStepClassification;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Rejects compile-output layouts that could overwrite or erase project inputs or another scope. */
public final class CompileOutputLayoutValidator {
    private CompileOutputLayoutValidator() {
    }

    public static void validateMain(Path projectDirectory, ProjectConfig config) {
        Path root = ProjectPaths.root(projectDirectory);
        BuildSettings build = config.build();
        validate(
                root,
                List.of(
                        output(root, "[build.output].main", build.output()),
                        output(root, "[compiler.generated].main", config.compilerSettings().generatedSources())),
                List.of(
                        output(root, "[build.output].test", build.testOutput()),
                        output(root, "[compiler.generated].test", config.compilerSettings().generatedTestSources()),
                        output(root, "[build.output].integration", build.integrationTestOutput())),
                protectedInputs(root, config));
    }

    public static void validateTest(Path projectDirectory, ProjectConfig config) {
        Path root = ProjectPaths.root(projectDirectory);
        BuildSettings build = config.build();
        List<ConfiguredPath> otherScope = new ArrayList<>();
        otherScope.add(output(root, "[build.output].main", build.output()));
        otherScope.add(output(root, "[compiler.generated].main", config.compilerSettings().generatedSources()));
        if (!isIntegrationProjection(build)) {
            // asIntegrationTestBuild() projects the integration output into testOutput; only in that
            // view do the two names denote the current scope. Callers must validate the original
            // settings before projecting so the original unit-test output is still visible.
            otherScope.add(output(root, "[build.output].integration", build.integrationTestOutput()));
        }
        validate(
                root,
                List.of(
                        output(root, "[build.output].test", build.testOutput()),
                        output(root, "[compiler.generated].test", config.compilerSettings().generatedTestSources())),
                List.copyOf(otherScope),
                protectedInputs(root, config));
    }

    private static boolean isIntegrationProjection(BuildSettings build) {
        return build.testOutput().equals(build.integrationTestOutput())
                && build.testSources().equals(build.integrationTestSources())
                && build.testResourceRoots().equals(build.integrationTestResourceRoots());
    }

    private static void validate(
            Path root,
            List<ConfiguredPath> owned,
            List<ConfiguredPath> otherScope,
            List<ConfiguredPath> protectedInputs) {
        for (ConfiguredPath output : owned) {
            Path comparableOutput = comparable(output.path());
            for (ConfiguredPath input : protectedInputs) {
                if (comparable(input.path()).startsWith(comparableOutput)) {
                    throw unsafe(output, input, "contains protected project input");
                }
            }
            for (ConfiguredPath other : otherScope) {
                Path comparableOther = comparable(other.path());
                if (comparableOutput.startsWith(comparableOther)
                        || comparableOther.startsWith(comparableOutput)) {
                    throw unsafe(output, other, "overlaps another compile scope");
                }
            }
        }
    }

    private static List<ConfiguredPath> protectedInputs(Path root, ProjectConfig config) {
        BuildSettings build = config.build();
        List<ConfiguredPath> paths = new ArrayList<>();
        paths.add(new ConfiguredPath("project manifest", "zolt.toml", root.resolve("zolt.toml")));
        addInputs(paths, root, "[build].sources", build.sourceRoots());
        addInputs(paths, root, "[test.sources].java", build.testSources());
        addInputs(paths, root, "[test.sources].groovy", build.groovyTestSources());
        addInputs(paths, root, "[test.integration].sources", build.integrationTestSources());
        addInputs(paths, root, "[resources].main", build.resourceRoots());
        addInputs(paths, root, "[resources].test", build.testResourceRoots());
        addInputs(paths, root, "[test.integration].resources", build.integrationTestResourceRoots());
        addGeneratedPaths(paths, root, build, "main", build.generatedMainSources());
        addGeneratedPaths(paths, root, build, "test", build.generatedTestSources());
        return List.copyOf(paths);
    }

    private static void addInputs(
            List<ConfiguredPath> paths,
            Path root,
            String key,
            List<String> configuredPaths) {
        for (int index = 0; index < configuredPaths.size(); index++) {
            String configured = configuredPaths.get(index);
            paths.add(input(root, key + "[" + index + "]", configured));
        }
    }

    private static void addGeneratedPaths(
            List<ConfiguredPath> paths,
            Path root,
            BuildSettings build,
            String scope,
            List<GeneratedSourceStep> steps) {
        for (GeneratedSourceStep step : steps) {
            String prefix = "[generated." + scope + "." + step.id() + "]";
            if (step.kind() == GeneratedSourceKind.OPENAPI) {
                step.openApi().config().ifPresent(configured ->
                        paths.add(input(root, prefix + ".config", configured)));
                step.openApi().templateDir().ifPresent(configured ->
                        paths.add(input(root, prefix + ".templateDir", configured)));
            }
            boolean postCompile = step.kind() == GeneratedSourceKind.EXEC
                    && ExecStepClassification.isPostCompile(step, root, build);
            if (!postCompile) {
                paths.add(output(root, prefix + ".output", step.output()));
            }
            for (int index = 0; index < step.inputs().size(); index++) {
                String input = step.inputs().get(index);
                if (postCompile && ExecStepClassification.isCompileOutputInput(input, root, build)) {
                    // Compiled output is the intended post-compile input and is recreated before the
                    // step runs. Other declared inputs remain protected from cleanup.
                    continue;
                }
                paths.add(input(root, prefix + ".inputs[" + index + "]", input));
            }
        }
    }

    private static ConfiguredPath input(Path root, String key, String configured) {
        try {
            return new ConfiguredPath(key, configured, ProjectPaths.input(root, key, configured));
        } catch (ProjectPathException exception) {
            throw new BuildException(exception.getMessage(), exception);
        }
    }

    private static ConfiguredPath output(Path root, String key, String configured) {
        try {
            return new ConfiguredPath(key, configured, ProjectPaths.output(root, key, configured));
        } catch (ProjectPathException exception) {
            throw new BuildException(exception.getMessage(), exception);
        }
    }

    /**
     * Resolves the deepest existing ancestor so aliases through in-project symlinks compare by their
     * real location even when the configured leaf does not exist yet.
     */
    private static Path comparable(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path ancestor = normalized;
        while (ancestor != null && !Files.exists(ancestor)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            return normalized;
        }
        try {
            return ancestor.toRealPath().resolve(ancestor.relativize(normalized)).normalize();
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not validate compile output layout at " + normalized
                            + ". Check that the project paths are readable.",
                    exception);
        }
    }

    private static BuildException unsafe(ConfiguredPath output, ConfiguredPath protectedPath, String relationship) {
        return new BuildException(
                "Unsafe compile output layout: " + output.key() + " path `" + output.configured()
                        + "` " + relationship + " " + protectedPath.key() + " path `"
                        + protectedPath.configured() + "`. Choose distinct output and input directories.");
    }

    private record ConfiguredPath(String key, String configured, Path path) {
    }
}
