package sh.zolt.build.resources;

import sh.zolt.build.ResourceCopyException;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProducesLane;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import sh.zolt.project.ResourceFilteringSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class ResourceCopier {
    private static final Set<String> OUTPUT_DIRECTORY_NAMES = Set.of("target", "build");

    public ResourceCopyResult copyMainResources(Path projectDirectory, BuildSettings settings) {
        return copyMainResources(projectDirectory, settings, Optional.empty());
    }

    public ResourceCopyResult copyMainResources(Path projectDirectory, ProjectConfig config) {
        return copyMainResources(projectDirectory, config.build(), Optional.of(config.project()));
    }

    private ResourceCopyResult copyMainResources(
            Path projectDirectory,
            BuildSettings settings,
            Optional<ProjectMetadata> project) {
        return copyResources(
                settings.resourceRoots(),
                outputDirectory(projectDirectory, "[build.output].main", settings.output()),
                projectDirectory,
                settings,
                project,
                "[resources].main",
                false);
    }

    public ResourceCopyResult copyTestResources(Path projectDirectory, BuildSettings settings) {
        return copyTestResources(projectDirectory, settings, Optional.empty());
    }

    public ResourceCopyResult copyTestResources(Path projectDirectory, ProjectConfig config) {
        return copyTestResources(projectDirectory, config.build(), Optional.of(config.project()));
    }

    private ResourceCopyResult copyTestResources(
            Path projectDirectory,
            BuildSettings settings,
            Optional<ProjectMetadata> project) {
        return copyResources(
                settings.testResourceRoots(),
                outputDirectory(projectDirectory, "[build.output].test", settings.testOutput()),
                projectDirectory,
                settings,
                project,
                "[resources].test",
                true);
    }

    private static ResourceCopyResult copyResources(
            List<String> configuredRoots,
            Path outputDirectory,
            Path projectDirectory,
            BuildSettings settings,
            Optional<ProjectMetadata> project,
            String resourceRootKey,
            boolean testResources) {
        List<ResourceCopyPlan.Entry> entries = planResources(configuredRoots, outputDirectory,
                projectDirectory, settings, project, resourceRootKey, testResources);
        List<Path> copied = new ArrayList<>();
        List<Path> skipped = new ArrayList<>();
        ResourceCopyPlan.copy(outputDirectory, entries, copied, skipped);
        return new ResourceCopyResult(copied, skipped);
    }

    public Set<Path> plannedOutputPaths(Path projectDirectory, ProjectConfig config, boolean testResources) {
        BuildSettings settings = config.build();
        return planResources(testResources ? settings.testResourceRoots() : settings.resourceRoots(),
                outputDirectory(projectDirectory, testResources ? "[build.output].test" : "[build.output].main",
                        testResources ? settings.testOutput() : settings.output()),
                projectDirectory, settings, Optional.of(config.project()),
                testResources ? "[resources].test" : "[resources].main", testResources)
                .stream().map(ResourceCopyPlan.Entry::relative).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static List<ResourceCopyPlan.Entry> planResources(
            List<String> configuredRoots,
            Path outputDirectory,
            Path projectDirectory,
            BuildSettings settings,
            Optional<ProjectMetadata> project,
            String resourceRootKey,
            boolean testResources) {
        Path projectRoot = ProjectPaths.root(projectDirectory);
        Path mainOutput = outputPath(projectRoot, "[build.output].main", settings.output());
        Path testOutput = outputPath(projectRoot, "[build.output].test", settings.testOutput());
        ResourceFilteringSettings filtering = settings.resourceFiltering();
        boolean filteringEnabled = filtering.enabled() && (!testResources || filtering.testEnabled());
        ResourceFilteringProcessor filteringProcessor =
                ResourceFilteringProcessor.create(filteringEnabled, filtering, project);
        List<ResourceCopyPlan.Entry> entries = new ArrayList<>();
        Set<Path> targetRelativePaths = new HashSet<>();
        for (String configuredRoot : configuredRoots) {
            Path resourceRoot = resourceRoot(projectRoot, resourceRootKey, configuredRoot);
            if (!Files.isDirectory(resourceRoot)) {
                continue;
            }

            try (Stream<Path> paths = Files.walk(resourceRoot)) {
                List<Path> resources = paths
                        .filter(path -> ProjectPaths.isRegularFileInsideProject(projectRoot, resourceRootKey, path))
                        .map(Path::normalize)
                        .filter(path -> !isSource(path))
                        .filter(path -> !path.startsWith(mainOutput))
                        .filter(path -> !path.startsWith(testOutput))
                        .filter(path -> !startsWithOutputDirectorySegment(resourceRoot.relativize(path)))
                        .sorted()
                        .toList();

                for (Path resource : resources) {
                    Path relativePath = resourceRoot.relativize(resource).normalize();
                    if (!targetRelativePaths.add(relativePath)) {
                        throw new ResourceCopyException(
                                "Duplicate resource path `"
                                        + relativePath.toString().replace('\\', '/')
                                        + "` from configured resource roots. Remove one copy or choose a distinct output path.");
                    }
                    entries.add(new ResourceCopyPlan.Entry(
                            resource, relativePath, filteringProcessor.filteredBytes(resource, relativePath)));
                }
            } catch (IOException exception) {
                throw new ResourceCopyException(
                        "Could not copy resources from "
                                + resourceRoot
                                + " to "
                                + outputDirectory
                                + ". Check that the project directories are readable and writable.",
                    exception);
            } catch (ProjectPathException exception) {
                throw new ResourceCopyException(exception.getMessage(), exception);
            }
        }
        copyExecResources(
                outputDirectory,
                projectRoot,
                execResourceSteps(settings, testResources),
                targetRelativePaths,
                entries);
        copyKspResources(
                outputDirectory,
                projectRoot,
                kspResourceSteps(settings, testResources),
                targetRelativePaths,
                entries,
                testResources ? "test" : "main");
        return List.copyOf(entries);
    }

    private static List<GeneratedSourceStep> execResourceSteps(BuildSettings settings, boolean testResources) {
        List<GeneratedSourceStep> steps = testResources
                ? settings.generatedTestSources()
                : settings.generatedMainSources();
        ProducesLane lane = testResources ? ProducesLane.TEST_RESOURCES : ProducesLane.RESOURCES;
        return steps.stream()
                .filter(step -> step.kind() == GeneratedSourceKind.EXEC)
                .filter(step -> step.exec().produces() == lane)
                .toList();
    }

    private static List<GeneratedSourceStep> kspResourceSteps(
            BuildSettings settings,
            boolean testResources) {
        List<GeneratedSourceStep> steps = testResources
                ? settings.generatedTestSources()
                : settings.generatedMainSources();
        return steps.stream()
                .filter(step -> step.kind() == GeneratedSourceKind.KSP)
                .toList();
    }

    private static boolean isSource(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".java")
                || fileName.endsWith(".groovy")
                || fileName.endsWith(".kt");
    }

    private static void copyExecResources(
            Path outputDirectory,
            Path projectRoot,
            List<GeneratedSourceStep> steps,
            Set<Path> targetRelativePaths,
            List<ResourceCopyPlan.Entry> entries) {
        for (GeneratedSourceStep step : steps) {
            Path output = outputPath(projectRoot, "[generated." + step.id() + "].output", step.output());
            if (!Files.isDirectory(output)) {
                continue;
            }
            Optional<Path> into = step.exec().into().map(Path::of);
            try (Stream<Path> paths = Files.walk(output)) {
                List<Path> files = paths.filter(Files::isRegularFile).map(Path::normalize).sorted().toList();
                for (Path file : files) {
                    Path relativePath = output.relativize(file).normalize();
                    Path targetRelativePath = into.map(prefix -> prefix.resolve(relativePath)).orElse(relativePath).normalize();
                    if (!targetRelativePaths.add(targetRelativePath)) {
                        throw new ResourceCopyException(
                                "Duplicate resource path `" + targetRelativePath.toString().replace('\\', '/')
                                        + "` from exec step [generated." + step.id()
                                        + "]. Choose a distinct into subtree or output path.");
                    }
                    entries.add(new ResourceCopyPlan.Entry(file, targetRelativePath, Optional.empty()));
                }
            } catch (IOException exception) {
                throw new ResourceCopyException(
                        "Could not copy exec resources from " + output + " to " + outputDirectory
                                + ". Check that the project directories are readable and writable.",
                        exception);
            }
        }
    }

    private static void copyKspResources(
            Path outputDirectory,
            Path projectRoot,
            List<GeneratedSourceStep> steps,
            Set<Path> targetRelativePaths,
            List<ResourceCopyPlan.Entry> entries,
            String scope) {
        for (GeneratedSourceStep step : steps) {
            String subject = "[generated." + scope + "." + step.id() + "]";
            Path base = outputPath(projectRoot, subject + ".output", step.output());
            Path resources = base.resolve("resources");
            if (!Files.isDirectory(resources)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(resources)) {
                List<Path> files = paths
                        .filter(path -> ProjectPaths.isRegularFileInsideProject(
                                projectRoot,
                                subject + ".resources",
                                path))
                        .map(Path::normalize)
                        .sorted()
                        .toList();
                for (Path file : files) {
                    Path relative = resources.relativize(file).normalize();
                    if (!targetRelativePaths.add(relative)) {
                        throw new ResourceCopyException(
                                "Duplicate resource path `" + relative.toString().replace('\\', '/')
                                        + "` from KSP step " + subject
                                        + ". Change the processor output or remove the conflicting resource.");
                    }
                    entries.add(new ResourceCopyPlan.Entry(file, relative, Optional.empty()));
                }
            } catch (IOException exception) {
                throw new ResourceCopyException(
                        "Could not copy KSP resources from " + resources + " to " + outputDirectory
                                + ". Check that the project directories are readable and writable.",
                        exception);
            } catch (ProjectPathException exception) {
                throw new ResourceCopyException(exception.getMessage(), exception);
            }
        }
    }

    private static Path outputDirectory(Path projectDirectory, String key, String configuredPath) {
        return outputPath(ProjectPaths.root(projectDirectory), key, configuredPath);
    }

    private static Path outputPath(Path projectRoot, String key, String configuredPath) {
        try {
            return ProjectPaths.output(projectRoot, key, configuredPath);
        } catch (ProjectPathException exception) {
            throw new ResourceCopyException(exception.getMessage(), exception);
        }
    }

    private static Path resourceRoot(Path projectRoot, String key, String configuredRoot) {
        try {
            return ProjectPaths.existingRoot(projectRoot, key, configuredRoot);
        } catch (ProjectPathException exception) {
            throw new ResourceCopyException(exception.getMessage(), exception);
        }
    }

    private static boolean startsWithOutputDirectorySegment(Path relativePath) {
        if (relativePath.getNameCount() == 0) {
            return false;
        }
        return OUTPUT_DIRECTORY_NAMES.contains(relativePath.getName(0).toString());
    }
}
