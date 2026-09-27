package sh.zolt.build.compile;

import sh.zolt.build.BuildException;
import sh.zolt.build.generatedsource.ExecStepClassification;
import sh.zolt.lockfile.ProjectLockfile;
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
import java.util.Collection;
import java.util.List;

/** Rejects compile-output layouts that could overwrite or erase project inputs or another scope. */
public final class CompileOutputLayoutValidator {
    private CompileOutputLayoutValidator() {
    }

    public static void validateMain(Path projectDirectory, ProjectConfig config) {
        Path root = ProjectPaths.root(projectDirectory);
        BuildSettings build = config.build();
        List<ConfiguredPath> owned = new ArrayList<>();
        owned.add(output(root, "[build.output].main", build.output()));
        owned.add(output(root, "[compiler.generated].main", config.compilerSettings().generatedSources()));
        addGeneratedOutputs(owned, root, "main", build.generatedMainSources());
        List<ConfiguredPath> otherScope = new ArrayList<>();
        otherScope.add(output(root, "[build.output].test", build.testOutput()));
        otherScope.add(output(root, "[compiler.generated].test", config.compilerSettings().generatedTestSources()));
        otherScope.add(output(root, "[build.output].integration", build.integrationTestOutput()));
        addGeneratedOutputs(otherScope, root, "test", build.generatedTestSources());
        validate(
                root,
                output(root, "[build.output].root", build.outputRoot()),
                List.copyOf(owned),
                List.copyOf(otherScope),
                protectedInputs(root, build, false));
    }

    public static void validateTest(Path projectDirectory, ProjectConfig config) {
        Path root = ProjectPaths.root(projectDirectory);
        BuildSettings build = config.build();
        List<ConfiguredPath> otherScope = new ArrayList<>();
        otherScope.add(output(root, "[build.output].main", build.output()));
        otherScope.add(output(root, "[compiler.generated].main", config.compilerSettings().generatedSources()));
        addGeneratedOutputs(otherScope, root, "main", build.generatedMainSources());
        if (!isIntegrationProjection(build)) {
            // asIntegrationTestBuild() projects the integration output into testOutput; only in that
            // view do the two names denote the current scope. Callers must validate the original
            // settings before projecting so the original unit-test output is still visible.
            otherScope.add(output(root, "[build.output].integration", build.integrationTestOutput()));
        }
        List<ConfiguredPath> owned = new ArrayList<>();
        owned.add(output(root, "[build.output].test", build.testOutput()));
        owned.add(output(root, "[compiler.generated].test", config.compilerSettings().generatedTestSources()));
        addGeneratedOutputs(owned, root, "test", build.generatedTestSources());
        validate(
                root,
                output(root, "[build.output].root", build.outputRoot()),
                List.copyOf(owned),
                List.copyOf(otherScope),
                protectedInputs(root, build, false));
    }

    /** Whether deleting {@code target} would erase a configured input or a generated root marked preserve. */
    public static boolean cleanTargetContainsProtectedInput(
            Path projectDirectory,
            BuildSettings build,
            Path target) {
        Path root = ProjectPaths.root(projectDirectory);
        ConfiguredPath cleanTarget = cleanTarget(root, target);
        Path normalizedTarget = cleanTarget.path().toAbsolutePath().normalize();
        Path comparableTarget = comparable(cleanTarget.path());
        return protectedInputs(root, build, true).stream()
                .map(ConfiguredPath::path)
                .anyMatch(path -> path.toAbsolutePath().normalize().startsWith(normalizedTarget)
                        || comparable(path).startsWith(comparableTarget));
    }

    /** Validates every final recursive clean target before the first filesystem mutation. */
    public static void validateCleanTargets(
            Path projectDirectory,
            BuildSettings build,
            Collection<Path> targets) {
        Path root = ProjectPaths.root(projectDirectory);
        ConfiguredPath outputRoot = output(root, "[build.output].root", build.outputRoot());
        List<ConfiguredPath> protectedInputs = protectedInputs(root, build, true);
        for (Path target : targets) {
            validateProtected(
                    root,
                    outputRoot,
                    cleanTarget(root, target),
                    protectedInputs,
                    "clean");
        }
    }

    private static boolean isIntegrationProjection(BuildSettings build) {
        return build.testOutput().equals(build.integrationTestOutput())
                && build.testSources().equals(build.integrationTestSources())
                && build.testResourceRoots().equals(build.integrationTestResourceRoots());
    }

    private static void validate(
            Path root,
            ConfiguredPath outputRoot,
            List<ConfiguredPath> owned,
            List<ConfiguredPath> otherScope,
            List<ConfiguredPath> protectedInputs) {
        for (ConfiguredPath output : owned) {
            validateProtected(root, outputRoot, output, protectedInputs, "compile");
            for (ConfiguredPath other : owned) {
                if (output != other && overlaps(output.path(), other.path())) {
                    throw unsafe("compile", output, other, "overlaps another owned output");
                }
            }
            for (ConfiguredPath other : otherScope) {
                if (overlaps(output.path(), other.path())) {
                    throw unsafe("compile", output, other, "overlaps another compile scope");
                }
            }
        }
    }

    private static void validateProtected(
            Path root,
            ConfiguredPath outputRoot,
            ConfiguredPath output,
            List<ConfiguredPath> protectedInputs,
            String operation) {
        Path comparableOutput = comparable(output.path());
        Path normalizedOutput = output.path().toAbsolutePath().normalize();
        for (ConfiguredPath input : protectedInputs) {
            Path comparableInput = comparable(input.path());
            Path normalizedInput = input.path().toAbsolutePath().normalize();
            if (normalizedInput.startsWith(normalizedOutput)
                    || comparableInput.startsWith(comparableOutput)) {
                throw unsafe(operation, output, input, "contains protected project input");
            }
            if ((normalizedOutput.startsWith(normalizedInput)
                            || comparableOutput.startsWith(comparableInput))
                    && !isOwnedProjectRootSubtree(
                            root, outputRoot, input, comparableOutput, comparableInput)) {
                throw unsafe(operation, output, input, "is nested within protected project input");
            }
        }
    }

    /**
     * A project-root input such as {@code [build].sources = ["."]} is intentionally broad. The declared
     * build output root carves an owned subtree out of that catch-all input, so conventional
     * {@code target/...} outputs remain valid. Narrower source/resource roots do not confer that ownership:
     * placing an output below one would make discovery hide files that cleanup can then erase. Neither a
     * source-root alias back to the project root nor an output-root alias into an authored tree can create
     * ownership.
     */
    private static boolean isOwnedProjectRootSubtree(
            Path root,
            ConfiguredPath outputRoot,
            ConfiguredPath input,
            Path comparableOutput,
            Path comparableInput) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedInput = input.path().toAbsolutePath().normalize();
        Path normalizedOutputRoot = outputRoot.path().toAbsolutePath().normalize();
        if (!input.allowsOutputRootSubtree()
                || !normalizedInput.equals(normalizedRoot)
                || !normalizedOutputRoot.startsWith(normalizedRoot)) {
            return false;
        }
        Path comparableRoot = comparable(normalizedRoot);
        Path expectedOutputRoot = comparableRoot
                .resolve(normalizedRoot.relativize(normalizedOutputRoot))
                .normalize();
        Path comparableOutputRoot = comparable(normalizedOutputRoot);
        return comparableInput.equals(comparableRoot)
                && comparableOutputRoot.equals(expectedOutputRoot)
                && comparableOutput.startsWith(comparableOutputRoot);
    }

    private static List<ConfiguredPath> protectedInputs(
            Path root,
            BuildSettings build,
            boolean cleanLayout) {
        List<ConfiguredPath> paths = new ArrayList<>();
        paths.add(new ConfiguredPath("project manifest", "zolt.toml", root.resolve("zolt.toml"), false));
        paths.add(new ConfiguredPath(
                "project lockfile",
                ProjectLockfile.NAME,
                root.resolve(ProjectLockfile.NAME),
                false));
        addInputs(paths, root, "[build].sources", build.sourceRoots());
        addInputs(paths, root, "[test.sources].java", build.testSources());
        addInputs(paths, root, "[test.sources].groovy", build.groovyTestSources());
        addInputs(paths, root, "[test.integration].sources", build.integrationTestSources());
        addInputs(paths, root, "[resources].main", build.resourceRoots());
        addInputs(paths, root, "[resources].test", build.testResourceRoots());
        addInputs(paths, root, "[test.integration].resources", build.integrationTestResourceRoots());
        addGeneratedPaths(paths, root, build, "main", build.generatedMainSources(), cleanLayout);
        addGeneratedPaths(paths, root, build, "test", build.generatedTestSources(), cleanLayout);
        return List.copyOf(paths);
    }

    private static void addInputs(
            List<ConfiguredPath> paths,
            Path root,
            String key,
            List<String> configuredPaths) {
        for (int index = 0; index < configuredPaths.size(); index++) {
            String configured = configuredPaths.get(index);
            paths.add(input(root, key + "[" + index + "]", configured, true));
        }
    }

    private static void addGeneratedPaths(
            List<ConfiguredPath> paths,
            Path root,
            BuildSettings build,
            String scope,
            List<GeneratedSourceStep> steps,
            boolean cleanLayout) {
        for (GeneratedSourceStep step : steps) {
            String prefix = "[generated." + scope + "." + step.id() + "]";
            if (step.kind() == GeneratedSourceKind.OPENAPI) {
                step.openApi().config().ifPresent(configured ->
                        paths.add(input(root, prefix + ".config", configured, false)));
                step.openApi().templateDir().ifPresent(configured ->
                        paths.add(input(root, prefix + ".templateDir", configured, false)));
            }
            boolean postCompile = step.kind() == GeneratedSourceKind.EXEC
                    && ExecStepClassification.isPostCompile(step, root, build);
            if ((cleanLayout && !step.clean())
                    || (!cleanLayout && step.kind() == GeneratedSourceKind.DECLARED_ROOT)) {
                paths.add(output(root, prefix + ".output", step.output()));
            }
            for (int index = 0; index < step.inputs().size(); index++) {
                String input = step.inputs().get(index);
                if (postCompile && ExecStepClassification.isCompileOutputInput(input, root, build)) {
                    // Compiled output is the intended post-compile input and is recreated before the
                    // step runs. Other declared inputs remain protected from cleanup.
                    continue;
                }
                ConfiguredPath configuredInput = input(root, prefix + ".inputs[" + index + "]", input, false);
                GeneratedOutputOwnership.protectedInput(root, scope, step, input, steps, configuredInput)
                        .ifPresent(paths::add);
            }
        }
    }

    private static void addGeneratedOutputs(
            List<ConfiguredPath> paths,
            Path root,
            String scope,
            List<GeneratedSourceStep> steps) {
        for (GeneratedSourceStep step : steps) {
            if (step.kind() == GeneratedSourceKind.DECLARED_ROOT) {
                continue;
            }
            paths.add(output(
                    root,
                    "[generated." + scope + "." + step.id() + "].output",
                    step.output()));
        }
    }

    private static boolean overlaps(Path first, Path second) {
        Path normalizedFirst = first.toAbsolutePath().normalize();
        Path normalizedSecond = second.toAbsolutePath().normalize();
        if (normalizedFirst.startsWith(normalizedSecond)
                || normalizedSecond.startsWith(normalizedFirst)) {
            return true;
        }
        Path comparableFirst = comparable(first);
        Path comparableSecond = comparable(second);
        return comparableFirst.startsWith(comparableSecond)
                || comparableSecond.startsWith(comparableFirst);
    }

    private static ConfiguredPath input(Path root, String key, String configured, boolean allowsOutputRootSubtree) {
        try {
            return new ConfiguredPath(
                    key, configured, ProjectPaths.input(root, key, configured), allowsOutputRootSubtree);
        } catch (ProjectPathException exception) {
            throw new BuildException(exception.getMessage(), exception);
        }
    }

    private static ConfiguredPath output(Path root, String key, String configured) {
        try {
            return new ConfiguredPath(key, configured, ProjectPaths.output(root, key, configured), false);
        } catch (ProjectPathException exception) {
            throw new BuildException(exception.getMessage(), exception);
        }
    }

    private static ConfiguredPath cleanTarget(Path root, Path target) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path comparableRoot = comparable(normalizedRoot);
        Path comparableTarget = comparable(normalizedTarget);
        if (normalizedTarget.equals(normalizedRoot)
                || !normalizedTarget.startsWith(normalizedRoot)
                || !comparableTarget.startsWith(comparableRoot)) {
            throw new BuildException(
                    "Unsafe clean output layout: clean target path `" + normalizedTarget
                            + "` is not a project-owned subtree under " + normalizedRoot + ".");
        }
        String configured = normalizedRoot.relativize(normalizedTarget).toString().replace('\\', '/');
        return new ConfiguredPath("clean target", configured, normalizedTarget, false);
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
                    "Could not validate output layout at " + normalized
                            + ". Check that the project paths are readable.",
                    exception);
        }
    }

    private static BuildException unsafe(
            String operation,
            ConfiguredPath output,
            ConfiguredPath protectedPath,
            String relationship) {
        return new BuildException(
                "Unsafe " + operation + " output layout: " + output.key() + " path `" + output.configured()
                        + "` " + relationship + " " + protectedPath.key() + " path `"
                        + protectedPath.configured() + "`. Choose distinct output and input directories.");
    }
}
