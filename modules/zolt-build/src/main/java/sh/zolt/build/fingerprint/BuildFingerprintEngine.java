package sh.zolt.build.fingerprint;

import sh.zolt.build.BuildException;
import sh.zolt.build.generatedsource.GeneratedSourceProducerFingerprint;
import sh.zolt.classpath.Classpath;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads, compares, and writes the on-disk compile fingerprint, and derives the inputs-only cache-key
 * hash. Owns the fingerprint content/comparison/expected-class/state collaborators so
 * {@link BuildFingerprintService} stays a thin scope-selecting facade over the main and test compile
 * scopes.
 */
final class BuildFingerprintEngine {
    private final BuildFingerprintContent content = new BuildFingerprintContent();
    private final BuildFingerprintComparison comparison = new BuildFingerprintComparison();
    private final BuildFingerprintExpectedClasses expectedClasses = new BuildFingerprintExpectedClasses();
    private final BuildFingerprintStateStore stateStore = new BuildFingerprintStateStore();

    String inputsFingerprintSha256(
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
            Path generatedSourcesDirectory) {
        String fingerprint = content.fingerprint(
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
                null,
                null,
                true);
        return BuildFingerprintInputs.inputsSha256(fingerprint);
    }

    BuildFingerprintCheck checkCompileCurrent(
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
            String fileName) {
        return checkCurrent(
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
                fileName,
                true);
    }

    BuildFingerprintCheck checkEvidenceCurrent(
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
            String fileName) {
        return checkCurrent(
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
                fileName,
                false);
    }

    private BuildFingerprintCheck checkCurrent(
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
            String fileName,
            boolean compilationOnly) {
        Path fingerprintPath = stateStore.fingerprintPath(outputDirectory, fileName);
        if (!Files.isRegularFile(fingerprintPath)) {
            return BuildFingerprintCheck.miss("missing-fingerprint");
        }
        if (!Files.isDirectory(outputDirectory)) {
            return BuildFingerprintCheck.miss("missing-output-directory");
        }
        if (!processorClasspath.entries().isEmpty() && !Files.isDirectory(generatedSourcesDirectory)) {
            return BuildFingerprintCheck.miss("missing-generated-sources-directory");
        }
        try {
            String existing = Files.readString(fingerprintPath);
            List<Path> missingExpectedClasses = expectedClasses.missing(
                    projectDirectory.toAbsolutePath().normalize(),
                    existing);
            if (!missingExpectedClasses.isEmpty()) {
                return BuildFingerprintCheck.miss("missing-expected-class:" + relative(
                        projectDirectory,
                        missingExpectedClasses.getFirst()));
            }
            Optional<BuildFingerprintState> state = stateStore.readState(fingerprintPath);
            if (state.isPresent() && state.orElseThrow().matchesFingerprint(existing)) {
                String current = content.fingerprint(
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
                        state.orElseThrow(),
                        null);
                return compare(existing, current, compilationOnly);
            }
            String current = content.fingerprint(
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
                    null,
                    null);
            return compare(existing, current, compilationOnly);
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not read build fingerprint at "
                            + fingerprintPath
                            + ". Delete the file or rerun `zolt build` to refresh it.",
                    exception);
        }
    }

    private BuildFingerprintCheck compare(String existing, String current, boolean compilationOnly) {
        return compilationOnly
                ? comparison.compareForCompilation(existing, current)
                : comparison.compare(existing, current);
    }

    void writeCompileFingerprint(
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
            String fileName) {
        Path fingerprintPath = stateStore.fingerprintPath(outputDirectory, fileName);
        try {
            Files.createDirectories(fingerprintPath.getParent());
            BuildFingerprintState cachedState = matchingState(fingerprintPath).orElse(null);
            Map<Path, BuildFingerprintCachedFileHash> state = new LinkedHashMap<>();
            String fingerprint = content.fingerprint(
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
                    state);
            Files.writeString(fingerprintPath, fingerprint, StandardCharsets.UTF_8);
            stateStore.writeState(fingerprintPath, fingerprint, state);
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not write build fingerprint at "
                            + fingerprintPath
                            + ". Check that the build output directory is writable.",
                    exception);
        }
    }

    private Optional<BuildFingerprintState> matchingState(Path fingerprintPath) throws IOException {
        if (!Files.isRegularFile(fingerprintPath)) {
            return Optional.empty();
        }
        String fingerprint = Files.readString(fingerprintPath, StandardCharsets.UTF_8);
        return stateStore.readState(fingerprintPath).filter(state -> state.matchesFingerprint(fingerprint));
    }

    private static String relative(Path projectDirectory, Path path) {
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(projectRoot)
                ? projectRoot.relativize(normalized).toString().replace('\\', '/')
                : normalized.toString().replace('\\', '/');
    }
}
