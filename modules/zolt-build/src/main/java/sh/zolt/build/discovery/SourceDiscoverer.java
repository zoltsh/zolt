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
        return discover(projectDirectory, settings, true, true);
    }

    /** Discovers only main sources, leaving owned test roots for the test-generation phase. */
    public SourceDiscoveryResult discoverMain(Path projectDirectory, BuildSettings settings) {
        return discover(projectDirectory, settings, false, true);
    }

    /** Discovers the source universe KSP consumes, excluding KSP's own not-yet-published lanes. */
    public SourceDiscoveryResult discoverMainBeforeKsp(
            Path projectDirectory,
            BuildSettings settings) {
        return discover(projectDirectory, settings, false, false);
    }

    private SourceDiscoveryResult discover(
            Path projectDirectory,
            BuildSettings settings,
            boolean includeTestSources,
            boolean includeKspOutputs) {
        Path projectRoot = ProjectPaths.root(projectDirectory);
        Path output = outputPath(projectRoot, "[build.output].main", settings.output());
        Path testOutput = outputPath(projectRoot, "[build.output].test", settings.testOutput());
        List<SourceRoot> authoredMainRoots = settings.sourceRoots().stream()
                .map(root -> inputRoot(projectRoot, "[build].sources", root))
                .toList();
        GeneratedRoots generatedMainRoots = generatedRoots(
                projectRoot, settings.generatedMainSources(), "main", includeKspOutputs);
        List<SourceRoot> mainJavaRoots = new ArrayList<>(authoredMainRoots);
        mainJavaRoots.addAll(generatedMainRoots.java());
        List<SourceRoot> mainKotlinRoots = new ArrayList<>(authoredMainRoots);
        mainKotlinRoots.addAll(generatedMainRoots.kotlin());
        List<Path> mainSources = discoverSources(
                projectRoot, mainJavaRoots, output, testOutput, ".java");
        List<Path> groovyMainSources = discoverSources(
                projectRoot, authoredMainRoots, output, testOutput, ".groovy");
        List<Path> kotlinMainSources = discoverSources(
                projectRoot, mainKotlinRoots, output, testOutput, ".kt");
        if (!includeTestSources) {
            return new SourceDiscoveryResult(
                    mainSources,
                    groovyMainSources,
                    kotlinMainSources,
                    List.of(),
                    List.of(),
                    List.of());
        }
        List<SourceRoot> authoredTestRoots = settings.testSources().stream()
                .map(root -> inputRoot(projectRoot, "[test.sources].java", root))
                .toList();
        List<SourceRoot> authoredGroovyTestRoots = settings.groovyTestSources().stream()
                .map(root -> inputRoot(projectRoot, "[test.sources].groovy", root))
                .toList();
        List<SourceRoot> authoredKotlinTestRoots = settings.kotlinTestSources().stream()
                .map(root -> inputRoot(projectRoot, "[test.sources].kotlin", root))
                .toList();
        GeneratedRoots generatedTestRoots = generatedRoots(
                projectRoot, settings.generatedTestSources(), "test", includeKspOutputs);
        List<SourceRoot> testJavaRoots = new ArrayList<>(authoredTestRoots);
        testJavaRoots.addAll(generatedTestRoots.java());
        List<SourceRoot> kotlinTestRoots = new ArrayList<>(authoredKotlinTestRoots);
        kotlinTestRoots.addAll(generatedTestRoots.kotlin());
        List<Path> kotlinTestSources = discoverSources(
                projectRoot, kotlinTestRoots, output, testOutput, ".kt");
        rejectMisplacedKotlinTests(
                projectRoot,
                authoredTestRoots,
                authoredGroovyTestRoots,
                kotlinTestSources,
                output,
                testOutput);
        return new SourceDiscoveryResult(
                mainSources,
                groovyMainSources,
                kotlinMainSources,
                discoverSources(projectRoot, testJavaRoots, output, testOutput, ".java"),
                discoverSources(projectRoot, authoredGroovyTestRoots, output, testOutput, ".groovy"),
                kotlinTestSources);
    }

    private static void rejectMisplacedKotlinTests(
            Path projectRoot,
            List<SourceRoot> javaRoots,
            List<SourceRoot> groovyRoots,
            List<Path> configuredKotlinSources,
            Path output,
            Path testOutput) {
        List<SourceRoot> legacyRoots = new ArrayList<>(javaRoots);
        legacyRoots.addAll(groovyRoots);
        Set<Path> admitted = Set.copyOf(configuredKotlinSources);
        List<Path> misplaced = discoverSources(projectRoot, legacyRoots, output, testOutput, ".kt")
                .stream()
                .filter(source -> !admitted.contains(source))
                .toList();
        if (misplaced.isEmpty()) {
            return;
        }
        Path first = misplaced.getFirst();
        String displayed = projectRoot.relativize(first).toString().replace('\\', '/');
        throw new SourceDiscoveryException(
                "Kotlin test source `" + displayed
                        + "` is under a Java or Groovy test root, but Kotlin test roots are explicit. "
                        + "Move it under a root declared in [test.sources].kotlin or declare its current root there.");
    }

    private static GeneratedRoots generatedRoots(
            Path projectRoot,
            List<GeneratedSourceStep> steps,
            String scope,
            boolean includeKspOutputs) {
        List<SourceRoot> javaRoots = new ArrayList<>();
        List<SourceRoot> kotlinRoots = new ArrayList<>();
        for (GeneratedSourceStep step : steps) {
            if (step.kind() == GeneratedSourceKind.KSP) {
                if (includeKspOutputs) {
                    KspDiscoveryRoots roots = KspDiscoveryRoots.resolve(projectRoot, scope, step);
                    javaRoots.add(new SourceRoot(roots.javaRoot(), roots.javaKey()));
                    kotlinRoots.add(new SourceRoot(roots.kotlinRoot(), roots.kotlinKey()));
                }
                continue;
            }
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
            List<SourceRoot> roots = languageRoots(step, scope, javaRoots, kotlinRoots);
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
        return new GeneratedRoots(javaRoots, kotlinRoots);
    }

    private static List<SourceRoot> languageRoots(
            GeneratedSourceStep step,
            String scope,
            List<SourceRoot> javaRoots,
            List<SourceRoot> kotlinRoots) {
        String subject = "[generated." + scope + "." + step.id() + "]";
        return switch (step.language()) {
            case "java" -> javaRoots;
            case "kotlin" -> {
                if (step.kind() != GeneratedSourceKind.DECLARED_ROOT
                        && step.kind() != GeneratedSourceKind.OPENAPI
                        && step.kind() != GeneratedSourceKind.PROTOBUF
                        && step.kind() != GeneratedSourceKind.EXEC) {
                    throw new SourceDiscoveryException(
                            "Generated source language `kotlin` for " + subject
                                    + " requires kind = \"declared-root\", kind = \"openapi\", kind ="
                                    + " \"protobuf\", or a"
                                    + " source-producing exec step.");
                }
                yield kotlinRoots;
            }
            default -> throw new SourceDiscoveryException(
                    "Unsupported generated source language `" + step.language() + "` for "
                            + subject
                            + ". Supported generated source languages are java and kotlin; kotlin requires"
                            + " kind = \"declared-root\", kind = \"openapi\", kind = \"protobuf\", or a"
                            + " source-producing exec step.");
        };
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

    private record GeneratedRoots(List<SourceRoot> java, List<SourceRoot> kotlin) {
        private GeneratedRoots {
            java = List.copyOf(java);
            kotlin = List.copyOf(kotlin);
        }
    }
}
