package sh.zolt.build.discovery;

import sh.zolt.build.SourceDiscoveryException;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProducesLane;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class SourceDiscoverer {
    private static final Set<String> OUTPUT_DIRECTORY_NAMES = Set.of("target", "build");

    public SourceDiscoveryResult discover(Path projectDirectory, BuildSettings settings) {
        Path projectRoot = ProjectPaths.root(projectDirectory);
        Path output = outputPath(projectRoot, "[build.output].main", settings.output());
        Path testOutput = outputPath(projectRoot, "[build.output].test", settings.testOutput());
        List<SourceRoot> mainRoots = new ArrayList<>(settings.sourceRoots().stream()
                .map(root -> inputRoot(projectRoot, "[build].sources", root))
                .toList());
        mainRoots.addAll(generatedRoots(projectRoot, settings.generatedMainSources(), "main"));
        List<SourceRoot> testRoots = new ArrayList<>(settings.testSources().stream()
                .map(root -> inputRoot(projectRoot, "[test.sources].java", root))
                .toList());
        testRoots.addAll(generatedRoots(projectRoot, settings.generatedTestSources(), "test"));
        return new SourceDiscoveryResult(
                discoverSources(projectRoot, mainRoots, output, testOutput, ".java"),
                discoverSources(projectRoot, testRoots, output, testOutput, ".java"),
                discoverSources(projectRoot, settings.groovyTestSources().stream()
                        .map(root -> inputRoot(projectRoot, "[test.sources].groovy", root))
                        .toList(), output, testOutput, ".groovy"));
    }

    private static List<SourceRoot> generatedRoots(
            Path projectRoot,
            List<GeneratedSourceStep> steps,
            String scope) {
        List<SourceRoot> roots = new ArrayList<>();
        for (GeneratedSourceStep step : steps) {
            if (step.kind() == GeneratedSourceKind.EXEC
                    && step.exec().produces() != ProducesLane.JAVA_SOURCES
                    && step.exec().produces() != ProducesLane.TEST_SOURCES) {
                // Only the source lanes join the compile source roots; resources/test-resources join
                // resource copying and intermediate is consumed by other steps via IO edges.
                continue;
            }
            if (step.kind() != GeneratedSourceKind.DECLARED_ROOT
                    && step.kind() != GeneratedSourceKind.OPENAPI
                    && step.kind() != GeneratedSourceKind.PROTOBUF
                    && step.kind() != GeneratedSourceKind.EXEC) {
                throw new SourceDiscoveryException(
                        "Unsupported generated source kind `"
                                + step.kind().configValue()
                                + "` for [generated."
                                + scope
                                + "."
                                + step.id()
                                + "]. Use declared-root for already generated Java sources.");
            }
            if (!"java".equals(step.language())) {
                throw new SourceDiscoveryException(
                        "Unsupported generated source language `"
                                + step.language()
                                + "` for [generated."
                                + scope
                                + "."
                                + step.id()
                                + "]. Supported generated source language is java.");
            }
            validateInputs(projectRoot, step, scope);
            String key = "[generated." + scope + "." + step.id() + "].output";
            Path output = outputPath(projectRoot, key, step.output(), scope, step.id(), "output");
            if (!Files.isDirectory(output)) {
                if (step.required()) {
                    throw new SourceDiscoveryException(
                            "Generated source root `"
                                    + step.output()
                                    + "` is missing. Run the generator that produces it, commit the generated sources, or remove [generated."
                                    + scope
                                    + "."
                                    + step.id()
                                    + "] until Zolt supports that generator.");
                }
                continue;
            }
            roots.add(new SourceRoot(output, key));
        }
        return roots;
    }

    private static void validateInputs(Path projectRoot, GeneratedSourceStep step, String scope) {
        for (String input : step.inputs()) {
            inputPath(projectRoot, "[generated." + scope + "." + step.id() + "].inputs", input, scope, step.id(), "inputs");
        }
    }

    private static SourceRoot inputRoot(Path projectRoot, String key, String configuredPath) {
        try {
            return new SourceRoot(ProjectPaths.existingRoot(projectRoot, key, configuredPath), key);
        } catch (ProjectPathException exception) {
            throw new SourceDiscoveryException(
                    exception.getMessage(),
                    exception);
        }
    }

    private static Path outputPath(Path projectRoot, String key, String configuredPath) {
        try {
            return ProjectPaths.output(projectRoot, key, configuredPath);
        } catch (ProjectPathException exception) {
            throw new SourceDiscoveryException(exception.getMessage(), exception);
        }
    }

    private static Path outputPath(
            Path projectRoot,
            String key,
            String configuredPath,
            String scope,
            String id,
            String field) {
        try {
            return ProjectPaths.output(projectRoot, key, configuredPath);
        } catch (ProjectPathException exception) {
            throw generatedSourcePathException(configuredPath, scope, id, field, exception);
        }
    }

    private static Path inputPath(
            Path projectRoot,
            String key,
            String configuredPath,
            String scope,
            String id,
            String field) {
        try {
            return ProjectPaths.input(projectRoot, key, configuredPath);
        } catch (ProjectPathException exception) {
            throw generatedSourcePathException(configuredPath, scope, id, field, exception);
        }
    }

    private static SourceDiscoveryException generatedSourcePathException(
            String configuredPath,
            String scope,
            String id,
            String field,
            ProjectPathException exception) {
        return new SourceDiscoveryException(
                "Invalid generated source "
                        + field
                        + " path `"
                        + configuredPath
                        + "` for [generated."
                        + scope
                        + "."
                        + id
                        + "]. "
                        + exception.getMessage(),
                exception);
    }

    private static List<Path> discoverSources(
            Path projectRoot,
            List<SourceRoot> roots,
            Path output,
            Path testOutput,
            String extension) {
        return roots.stream()
                .flatMap(root -> discoverSourcesFromRoot(projectRoot, root, output, testOutput, extension).stream())
                .distinct()
                .sorted()
                .toList();
    }

    private static List<Path> discoverSourcesFromRoot(
            Path projectRoot,
            SourceRoot root,
            Path output,
            Path testOutput,
            String extension) {
        if (!Files.isDirectory(root.path())) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root.path())) {
            return paths
                    .filter(path -> ProjectPaths.isRegularFileInsideProject(projectRoot, root.key(), path))
                    .filter(path -> path.getFileName().toString().endsWith(extension))
                    .map(Path::normalize)
                    .filter(path -> !path.startsWith(output))
                    .filter(path -> !path.startsWith(testOutput))
                    .filter(path -> !startsWithOutputDirectorySegment(root.path().relativize(path)))
                    .sorted()
                    .toList();
        } catch (ProjectPathException exception) {
            throw new SourceDiscoveryException(exception.getMessage(), exception);
        } catch (IOException exception) {
            throw new SourceDiscoveryException(
                    "Could not discover sources under "
                            + root.path()
                            + ". Check that the directory is readable.",
                    exception);
        }
    }

    private static boolean startsWithOutputDirectorySegment(Path relativePath) {
        if (relativePath.getNameCount() == 0) {
            return false;
        }
        return OUTPUT_DIRECTORY_NAMES.contains(relativePath.getName(0).toString());
    }

    private record SourceRoot(Path path, String key) {
    }
}
