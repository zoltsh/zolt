package sh.zolt.build.fingerprint;

import sh.zolt.build.BuildException;
import sh.zolt.build.generatedsource.ExecStepClassification;
import sh.zolt.build.generatedsource.GeneratedSourceProducerFingerprint;
import sh.zolt.classpath.Classpath;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

final class BuildFingerprintContent {
    private static final String VERSION = "1";
    private static final List<String> OUTPUT_DIRECTORY_NAMES = List.of("build", "target");

    private final BuildFingerprintExpectedClasses expectedClasses = new BuildFingerprintExpectedClasses();
    private final BuildFingerprintFileHasher fileHasher = new BuildFingerprintFileHasher();

    String fingerprint(
            Path projectDirectory,
            ProjectConfig config,
            Path lockfilePath,
            List<String> sourceRoots,
            List<String> resourceRoots,
            String resourceRootKey,
            List<Path> sources,
            List<GeneratedSourceStep> generatedSteps,
            List<GeneratedSourceProducerFingerprint> generatedProducerFingerprints,
            Classpath compileClasspath,
            Classpath processorClasspath,
            Path outputDirectory,
            String outputDirectoryName,
            Path generatedSourcesDirectory,
            BuildFingerprintState cachedState,
            Map<Path, BuildFingerprintCachedFileHash> collectedState) {
        return fingerprint(
                projectDirectory,
                config,
                lockfilePath,
                sourceRoots,
                resourceRoots,
                resourceRootKey,
                sources,
                generatedSteps,
                generatedProducerFingerprints,
                compileClasspath,
                processorClasspath,
                outputDirectory,
                outputDirectoryName,
                generatedSourcesDirectory,
                cachedState,
                collectedState,
                false);
    }

    String fingerprint(
            Path projectDirectory,
            ProjectConfig config,
            Path lockfilePath,
            List<String> sourceRoots,
            List<String> resourceRoots,
            String resourceRootKey,
            List<Path> sources,
            List<GeneratedSourceStep> generatedSteps,
            List<GeneratedSourceProducerFingerprint> generatedProducerFingerprints,
            Classpath compileClasspath,
            Classpath processorClasspath,
            Path outputDirectory,
            String outputDirectoryName,
            Path generatedSourcesDirectory,
            BuildFingerprintState cachedState,
            Map<Path, BuildFingerprintCachedFileHash> collectedState,
            boolean cacheKeyMode) {
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        StringBuilder content = new StringBuilder();
        line(content, "version", VERSION);
        line(content, "projectJava", config.project().java());
        line(content, "zoltToml", fileHasher.fileHash(projectRoot.resolve("zolt.toml"), cachedState, collectedState));
        line(content, "lockfile", fileHasher.fileHash(lockfilePath, cachedState, collectedState));
        section(content, "sourceRoots", sourceRoots.stream().map(BuildFingerprintContent::normalize).toList());
        line(content, "outputDirectory", normalize(outputDirectoryName));
        line(content, "generatedSourcesDirectory", fileHasher.relative(projectRoot, generatedSourcesDirectory));
        line(content, "compilerSettings", config.compilerSettings().toString());
        section(content, "compileClasspath", classpathEntries(compileClasspath, cachedState, collectedState, cacheKeyMode));
        section(content, "processorClasspath", classpathEntries(processorClasspath, cachedState, collectedState, cacheKeyMode));
        section(content, "sources", fileEntries(projectRoot, sources, cachedState, collectedState));
        section(content, "generatedProducerFingerprints", generatedProducerEntries(generatedProducerFingerprints));
        section(content, "generatedSourceInputs", generatedSourceInputEntries(projectRoot, generatedSteps, cachedState, collectedState));
        section(content, "generatedSources", generatedSourceEntries(projectRoot, generatedSourcesDirectory, cachedState, collectedState));
        section(content, "execOutputs", execOutputEntries(projectRoot, config.build().outputRoot(), generatedSteps, cachedState, collectedState));
        section(content, "resources", resourceEntries(
                projectRoot,
                resourceRoots,
                resourceRootKey,
                config.build(),
                cachedState,
                collectedState));
        section(content, "expectedClasses", expectedClasses.entries(projectRoot, sourceRoots, sources, outputDirectory));
        return content.toString();
    }

    private static List<String> generatedProducerEntries(
            List<GeneratedSourceProducerFingerprint> fingerprints) {
        return fingerprints.stream()
                .map(fingerprint -> fingerprint.scope()
                        + "|"
                        + fingerprint.stepId()
                        + "|"
                        + fingerprint.kind().configValue()
                        + "|"
                        + fingerprint.fingerprint())
                .toList();
    }

    private List<String> classpathEntries(
            Classpath classpath,
            BuildFingerprintState cachedState,
            Map<Path, BuildFingerprintCachedFileHash> collectedState,
            boolean cacheKeyMode) {
        if (cacheKeyMode) {
            // Content-only, path-free entries: two builds with the same compiled dependencies key
            // identically regardless of where those artifacts live (machine, checkout) or whether a
            // dependency output was compiled or restored. Sorted by content so order does not matter,
            // which the skip-gate fingerprint already disregards (it sorts entries too).
            return classpath.entries().stream()
                    .map(path -> path.toAbsolutePath().normalize())
                    .map(path -> fileHasher.classpathKeyHash(path, cachedState, collectedState))
                    .sorted()
                    .toList();
        }
        return classpath.entries().stream()
                .map(path -> path.toAbsolutePath().normalize())
                .sorted()
                .map(path -> path + "|" + fileHasher.classpathHash(path, cachedState, collectedState))
                .toList();
    }

    private List<String> fileEntries(
            Path projectRoot,
            List<Path> files,
            BuildFingerprintState cachedState,
            Map<Path, BuildFingerprintCachedFileHash> collectedState) {
        return files.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .sorted()
                .map(path -> fileHasher.relative(projectRoot, path) + "|"
                        + fileHasher.fileHash(path, cachedState, collectedState))
                .toList();
    }

    private List<String> generatedSourceInputEntries(
            Path projectRoot,
            List<GeneratedSourceStep> steps,
            BuildFingerprintState cachedState,
            Map<Path, BuildFingerprintCachedFileHash> collectedState) {
        return steps.stream()
                .flatMap(step -> step.inputs().stream()
                        .map(input -> inputPath(projectRoot, "[generated." + step.id() + "].inputs", input)))
                .distinct()
                .sorted()
                .map(path -> fileHasher.relative(projectRoot, path) + "|"
                        + fileHasher.fileHash(path, cachedState, collectedState))
                .toList();
    }

    private List<String> generatedSourceEntries(
            Path projectRoot,
            Path generatedSourcesDirectory,
            BuildFingerprintState cachedState,
            Map<Path, BuildFingerprintCachedFileHash> collectedState) {
        if (!Files.isDirectory(generatedSourcesDirectory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(generatedSourcesDirectory)) {
            return fileEntries(
                    projectRoot,
                    paths.filter(Files::isRegularFile)
                            .map(Path::normalize)
                            .filter(path -> path.getFileName().toString().endsWith(".java"))
                            .toList(),
                    cachedState,
                    collectedState);
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not fingerprint generated sources under "
                            + generatedSourcesDirectory
                            + ". Check that the directory is readable.",
                    exception);
        }
    }

    private List<String> execOutputEntries(
            Path projectRoot,
            String outputRoot,
            List<GeneratedSourceStep> steps,
            BuildFingerprintState cachedState,
            Map<Path, BuildFingerprintCachedFileHash> collectedState) {
        List<Path> files = new ArrayList<>();
        for (GeneratedSourceStep step : steps) {
            if (step.kind() != GeneratedSourceKind.EXEC) {
                continue;
            }
            // Post-compile exec outputs (project runner / inputs under compiled classes) are produced
            // AFTER compile; hashing them into the compile fingerprint that gates compile would create a
            // cycle and break the double-build skip. Their consumer fence lives in package/test evidence.
            if (ExecStepClassification.isPostCompile(step, projectRoot, outputRoot)) {
                continue;
            }
            Path output = outputPath(projectRoot, "[generated." + step.id() + "].output", step.output());
            if (!Files.isDirectory(output)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(output)) {
                paths.filter(Files::isRegularFile).map(Path::normalize).forEach(files::add);
            } catch (IOException exception) {
                throw new BuildException(
                        "Could not fingerprint exec output under " + output + ". Check that the directory is readable.",
                        exception);
            }
        }
        return fileEntries(projectRoot, files, cachedState, collectedState);
    }

    private List<String> resourceEntries(
            Path projectRoot,
            List<String> configuredRoots,
            String resourceRootKey,
            BuildSettings settings,
            BuildFingerprintState cachedState,
            Map<Path, BuildFingerprintCachedFileHash> collectedState) {
        List<Path> resources = new ArrayList<>();
        Path mainOutput = outputPath(projectRoot, "[build].output", settings.output());
        Path testOutput = outputPath(projectRoot, "[build].testOutput", settings.testOutput());
        for (String configuredRoot : configuredRoots) {
            Path root = existingRoot(projectRoot, resourceRootKey, configuredRoot);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .map(Path::normalize)
                        .filter(path -> !path.getFileName().toString().endsWith(".java"))
                        .filter(path -> !path.startsWith(mainOutput))
                        .filter(path -> !path.startsWith(testOutput))
                        .filter(path -> !startsWithOutputDirectorySegment(root.relativize(path)))
                        .forEach(resources::add);
            } catch (IOException exception) {
                throw new BuildException(
                        "Could not fingerprint resources under "
                                + root
                                + ". Check that the directory is readable.",
                        exception);
            }
        }
        return fileEntries(projectRoot, resources, cachedState, collectedState);
    }

    private static void section(StringBuilder content, String name, List<String> entries) {
        content.append('[').append(name).append(']').append('\n');
        entries.stream().sorted(Comparator.naturalOrder()).forEach(entry -> content.append(entry).append('\n'));
    }

    private static void line(StringBuilder content, String name, String value) {
        content.append(name).append('=').append(value).append('\n');
    }

    private static String normalize(String value) {
        return Path.of(value).normalize().toString().replace('\\', '/');
    }

    private static Path inputPath(Path projectRoot, String key, String configuredPath) {
        try {
            return ProjectPaths.input(projectRoot, key, configuredPath);
        } catch (ProjectPathException exception) {
            throw new BuildException(exception.getMessage(), exception);
        }
    }

    private static Path outputPath(Path projectRoot, String key, String configuredPath) {
        try {
            return ProjectPaths.output(projectRoot, key, configuredPath);
        } catch (ProjectPathException exception) {
            throw new BuildException(exception.getMessage(), exception);
        }
    }

    private static Path existingRoot(Path projectRoot, String key, String configuredPath) {
        try {
            return ProjectPaths.existingRoot(projectRoot, key, configuredPath);
        } catch (ProjectPathException exception) {
            throw new BuildException(exception.getMessage(), exception);
        }
    }

    private static boolean startsWithOutputDirectorySegment(Path relativePath) {
        return relativePath.getNameCount() > 0
                && OUTPUT_DIRECTORY_NAMES.contains(relativePath.getName(0).toString());
    }

}
