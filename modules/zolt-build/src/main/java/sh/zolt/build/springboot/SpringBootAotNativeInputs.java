package sh.zolt.build.springboot;

import sh.zolt.build.NativeImageException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class SpringBootAotNativeInputs {
    private final Path projectRoot;
    private final String outputRoot;
    private final List<Path> freshnessInputs;
    private final SpringBootAotOutputEvidenceService evidenceService;

    public SpringBootAotNativeInputs(Path projectRoot) {
        this(projectRoot, "target");
    }

    public SpringBootAotNativeInputs(Path projectRoot, String outputRoot) {
        this(projectRoot, outputRoot, List.of(), new SpringBootAotOutputEvidenceService());
    }

    public SpringBootAotNativeInputs(Path projectRoot, String outputRoot, List<Path> freshnessInputs) {
        this(projectRoot, outputRoot, freshnessInputs, new SpringBootAotOutputEvidenceService());
    }

    SpringBootAotNativeInputs(
            Path projectRoot,
            String outputRoot,
            List<Path> freshnessInputs,
            SpringBootAotOutputEvidenceService evidenceService) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.outputRoot = outputRoot == null || outputRoot.isBlank() ? "target" : outputRoot;
        this.freshnessInputs = freshnessInputs == null ? List.of() : List.copyOf(freshnessInputs);
        this.evidenceService = evidenceService;
    }

    public List<Path> classpathEntries() {
        SpringBootAotOutputEvidence evidence = evidenceService.collect(projectRoot, outputRoot);
        requireDirectory(evidence.sourcesDirectory(), "Spring Boot AOT sources");
        requireFiles(evidence.generatedSources(), "Spring Boot AOT generated source files", evidence.sourcesDirectory());
        requireDirectory(evidence.classesDirectory(), "compiled Spring Boot AOT classes");
        requireFiles(evidence.generatedClasses(), "compiled Spring Boot AOT class files", evidence.classesDirectory());
        requireDirectory(evidence.resourcesDirectory(), "Spring Boot AOT resources");
        requireDirectory(evidence.nativeMetadataDirectory(), "Spring Boot AOT native metadata");
        requireFiles(evidence.reflectionMetadata(), "Spring Boot AOT reflection metadata", evidence.nativeMetadataDirectory());
        requireFresh(evidence);
        return List.of(evidence.classesDirectory(), evidence.resourcesDirectory());
    }

    private static void requireDirectory(Path path, String label) {
        if (!Files.isDirectory(path)) {
            throw new NativeImageException(
                    "Spring Boot native AOT output is missing "
                            + label
                            + " at "
                            + path
                            + ". [framework.spring-boot] native = true requires Spring AOT outputs under the configured build output root before invoking Native Image.");
        }
    }

    private static void requireFiles(List<Path> paths, String label, Path directory) {
        if (paths.isEmpty()) {
            throw new NativeImageException(
                    "Spring Boot native AOT output is missing "
                            + label
                            + " under "
                            + directory
                            + ". [framework.spring-boot] native = true requires complete Spring AOT output evidence before invoking Native Image.");
        }
    }

    private void requireFresh(SpringBootAotOutputEvidence evidence) {
        List<Path> aotFiles = new ArrayList<>();
        aotFiles.addAll(evidence.generatedSources());
        aotFiles.addAll(evidence.generatedClasses());
        aotFiles.addAll(evidence.generatedResources());
        aotFiles.addAll(evidence.reflectionMetadata());
        aotFiles.addAll(evidence.reachabilityMetadata());
        Optional<FileTime> oldestAot = oldestTime(aotFiles);
        Optional<FileTime> newestInput = newestTime(freshnessInputs);
        if (oldestAot.isPresent()
                && newestInput.isPresent()
                && oldestAot.orElseThrow().compareTo(newestInput.orElseThrow()) < 0) {
            throw new NativeImageException(
                    "Spring Boot native AOT output is stale under "
                            + evidence.outputRoot()
                            + ". Run `zolt build` to regenerate Spring AOT output before invoking Native Image.");
        }
    }

    private static Optional<FileTime> oldestTime(List<Path> paths) {
        return paths.stream()
                .map(SpringBootAotNativeInputs::lastModified)
                .flatMap(Optional::stream)
                .min(FileTime::compareTo);
    }

    private static Optional<FileTime> newestTime(List<Path> paths) {
        return paths.stream()
                .flatMap(path -> Files.isDirectory(path) ? files(path).stream() : Stream.of(path))
                .map(SpringBootAotNativeInputs::lastModified)
                .flatMap(Optional::stream)
                .max(FileTime::compareTo);
    }

    private static Optional<FileTime> lastModified(Path path) {
        try {
            return Files.exists(path) ? Optional.of(Files.getLastModifiedTime(path)) : Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static List<Path> files(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith(".zolt-"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }
}
