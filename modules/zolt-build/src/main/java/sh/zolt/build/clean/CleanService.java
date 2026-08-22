package sh.zolt.build.clean;

import sh.zolt.build.CleanException;
import sh.zolt.framework.FrameworkCleanTargets;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class CleanService {
    private final FrameworkCleanTargets frameworkCleanTargets;

    public CleanService() {
        this(new FrameworkCleanTargets());
    }

    CleanService(FrameworkCleanTargets frameworkCleanTargets) {
        this.frameworkCleanTargets = frameworkCleanTargets;
    }

    public CleanResult clean(Path projectDirectory, BuildSettings settings) {
        return clean(projectDirectory, settings, CompilerSettings.defaults());
    }

    public CleanResult clean(Path projectDirectory, BuildSettings settings, CompilerSettings compilerSettings) {
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        Set<Path> targets = cleanTargets(projectRoot, settings, compilerSettings);
        return cleanTargets(targets);
    }

    public CleanResult clean(Path projectDirectory, ProjectConfig config) {
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        Set<Path> targets = cleanTargets(projectRoot, config.build(), config.compilerSettings());
        targets.addAll(frameworkCleanTargets.cleanTargets(projectRoot, config));
        return cleanTargets(targets);
    }

    private static CleanResult cleanTargets(Set<Path> targets) {
        ArrayList<Path> deleted = new ArrayList<>();
        for (Path target : targets) {
            if (!Files.exists(target)) {
                continue;
            }
            deleteRecursively(target);
            deleted.add(target);
        }
        return new CleanResult(deleted);
    }

    private static Set<Path> cleanTargets(Path projectRoot, BuildSettings settings, CompilerSettings compilerSettings) {
        Path output = safeProjectPath(projectRoot, "[build.output].main", settings.output());
        Path testOutput = safeProjectPath(projectRoot, "[build.output].test", settings.testOutput());
        Path generatedSources = safeProjectPath(
                projectRoot,
                "[compiler.generated].main",
                compilerSettings.generatedSources());
        Path generatedTestSources = safeProjectPath(
                projectRoot,
                "[compiler.generated].test",
                compilerSettings.generatedTestSources());
        Path sharedParent = sharedOutputParent(output, testOutput).orElse(null);
        Set<Path> targets = new LinkedHashSet<>();
        Set<Path> protectedGeneratedRoots = protectedGeneratedRoots(projectRoot, settings);
        if (sharedParent != null && isBuildOutputParent(sharedParent) && protectedGeneratedRoots.stream()
                .noneMatch(path -> path.startsWith(sharedParent))) {
            targets.add(sharedParent);
        } else {
            targets.add(output);
            targets.add(testOutput);
        }
        targets.add(generatedSources);
        targets.add(generatedTestSources);
        settings.generatedMainSources().stream()
                .filter(GeneratedSourceStep::clean)
                .map(step -> safeProjectPath(projectRoot, "[generated.main." + step.id() + "].output", step.output()))
                .forEach(targets::add);
        settings.generatedTestSources().stream()
                .filter(GeneratedSourceStep::clean)
                .map(step -> safeProjectPath(projectRoot, "[generated.test." + step.id() + "].output", step.output()))
                .forEach(targets::add);
        return targets;
    }

    private static Set<Path> protectedGeneratedRoots(Path projectRoot, BuildSettings settings) {
        Set<Path> roots = new LinkedHashSet<>();
        settings.generatedMainSources().stream()
                .filter(step -> !step.clean())
                .map(step -> safeProjectPath(projectRoot, "[generated.main." + step.id() + "].output", step.output()))
                .forEach(roots::add);
        settings.generatedTestSources().stream()
                .filter(step -> !step.clean())
                .map(step -> safeProjectPath(projectRoot, "[generated.test." + step.id() + "].output", step.output()))
                .forEach(roots::add);
        return roots;
    }

    private static Optional<Path> sharedOutputParent(Path output, Path testOutput) {
        Path outputParent = output.getParent();
        Path testOutputParent = testOutput.getParent();
        if (outputParent != null && outputParent.equals(testOutputParent)) {
            return Optional.of(outputParent);
        }
        return Optional.empty();
    }

    private static boolean isBuildOutputParent(Path path) {
        Path name = path.getFileName();
        return name != null && ("target".equals(name.toString()) || "build".equals(name.toString()));
    }

    private static Path safeProjectPath(Path projectRoot, String key, String configuredPath) {
        try {
            return ProjectPaths.output(projectRoot, key, configuredPath);
        } catch (ProjectPathException exception) {
            throw new CleanException(exception.getMessage(), exception);
        }
    }

    private static void deleteRecursively(Path target) {
        try (Stream<Path> paths = Files.walk(target)) {
            List<Path> sorted = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : sorted) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw new CleanException(
                    "Could not delete build output at "
                            + target
                            + ". Check filesystem permissions and try again.",
                    exception);
        }
    }
}
