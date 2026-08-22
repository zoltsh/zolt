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
import java.nio.file.StandardCopyOption;
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
        Path projectRoot = ProjectPaths.root(projectDirectory);
        Path mainOutput = outputPath(projectRoot, "[build.output].main", settings.output());
        Path testOutput = outputPath(projectRoot, "[build.output].test", settings.testOutput());
        ResourceFilteringSettings filtering = settings.resourceFiltering();
        boolean filteringEnabled = filtering.enabled() && (!testResources || filtering.testEnabled());
        ResourceFilteringProcessor filteringProcessor =
                ResourceFilteringProcessor.create(filteringEnabled, filtering, project);
        List<Path> copiedResources = new ArrayList<>();
        List<Path> skippedResources = new ArrayList<>();
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
                        .filter(path -> !path.getFileName().toString().endsWith(".java"))
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
                    Path target = outputDirectory.resolve(relativePath).normalize();
                    Files.createDirectories(target.getParent());
                    Optional<byte[]> filtered = filteringProcessor.filteredBytes(resource, relativePath);
                    if (isCurrent(resource, target, filtered)) {
                        skippedResources.add(resource);
                    } else if (filtered.isPresent()) {
                        Files.write(target, filtered.orElseThrow());
                        copiedResources.add(resource);
                    } else {
                        Files.copy(resource, target, StandardCopyOption.REPLACE_EXISTING);
                        copiedResources.add(resource);
                    }
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
                copiedResources,
                skippedResources);
        return new ResourceCopyResult(copiedResources, skippedResources);
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

    private static void copyExecResources(
            Path outputDirectory,
            Path projectRoot,
            List<GeneratedSourceStep> steps,
            Set<Path> targetRelativePaths,
            List<Path> copiedResources,
            List<Path> skippedResources) {
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
                    Path target = outputDirectory.resolve(targetRelativePath).normalize();
                    Files.createDirectories(target.getParent());
                    if (isCurrent(file, target, Optional.empty())) {
                        skippedResources.add(file);
                    } else {
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                        copiedResources.add(file);
                    }
                }
            } catch (IOException exception) {
                throw new ResourceCopyException(
                        "Could not copy exec resources from " + output + " to " + outputDirectory
                                + ". Check that the project directories are readable and writable.",
                        exception);
            }
        }
    }

    private static boolean isCurrent(Path source, Path target, Optional<byte[]> filteredContent) throws IOException {
        if (filteredContent.isPresent()) {
            byte[] content = filteredContent.orElseThrow();
            return Files.isRegularFile(target)
                    && Files.size(target) == content.length
                    && java.util.Arrays.equals(Files.readAllBytes(target), content);
        }
        return Files.isRegularFile(target)
                && Files.size(source) == Files.size(target)
                && Files.mismatch(source, target) == -1L;
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
