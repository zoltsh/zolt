package sh.zolt.plan;

import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

final class SpringBootNativePlanStateFactory {
    private final ZoltLockfileReader lockfileReader;

    SpringBootNativePlanStateFactory() {
        this(new ZoltLockfileReader());
    }

    SpringBootNativePlanStateFactory(ZoltLockfileReader lockfileReader) {
        this.lockfileReader = lockfileReader;
    }

    /** {@code lockfilePath} is the authoritative lockfile the caller resolved (design §6.9). */
    SpringBootNativePlanState state(
            Path root,
            Path lockfile,
            ProjectConfig config,
            Optional<Path> nativeImageExecutable) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        String outputRoot = outputRoot(config.build());
        Path aotRoot = normalizedRoot.resolve(outputRoot).resolve("spring-aot/main").normalize();
        Path sources = aotRoot.resolve("sources").normalize();
        Path classes = aotRoot.resolve("classes").normalize();
        Path resources = aotRoot.resolve("resources").normalize();
        Path metadata = resources.resolve("META-INF/native-image").normalize();
        List<Path> generatedSources = files(sources, ".java");
        List<Path> generatedClasses = files(classes, ".class");
        List<Path> reflectionMetadata = namedFiles(metadata, "reflect-config.json");
        List<Path> reachabilityMetadata = namedFiles(metadata, "reachability-metadata.json");
        Path lockfilePath = lockfile.toAbsolutePath().normalize();
        NativeSettings nativeSettings = config.nativeSettings().withDefaultImageName(config.project().name());
        Path outputDirectory = normalizedRoot.resolve(nativeSettings.output()).normalize();
        Path executable = nativeImageExecutable.orElse(Path.of("native-image"));
        return new SpringBootNativePlanState(
                normalizedRoot,
                lockfilePath,
                lockfile(lockfilePath),
                lockfileError(lockfilePath),
                aotRoot,
                sources,
                classes,
                resources,
                metadata,
                generatedSources,
                generatedClasses,
                reflectionMetadata,
                reachabilityMetadata,
                normalizedRoot.resolve(jarPath(config)).normalize(),
                outputDirectory,
                outputDirectory.resolve(nativeSettings.imageName()).normalize(),
                outputDirectory.resolve("native-image.log").normalize(),
                outputDirectory.resolve("spring-aot-evidence.json").normalize(),
                executable,
                nativeImageAvailable(normalizedRoot, executable),
                SpringBootNativeProjectDetector.springBootVersion(config),
                aotFreshness(normalizedRoot, config, generatedSources, generatedClasses, reflectionMetadata, reachabilityMetadata));
    }

    private Optional<ZoltLockfile> lockfile(Path lockfilePath) {
        if (!Files.isRegularFile(lockfilePath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(lockfileReader.read(lockfilePath));
        } catch (LockfileReadException exception) {
            return Optional.empty();
        }
    }

    private Optional<String> lockfileError(Path lockfilePath) {
        if (!Files.isRegularFile(lockfilePath)) {
            return Optional.empty();
        }
        try {
            lockfileReader.read(lockfilePath);
            return Optional.empty();
        } catch (LockfileReadException exception) {
            return Optional.of(exception.getMessage());
        }
    }

    private static Path jarPath(ProjectConfig config) {
        String outputRoot = outputRoot(config.build());
        return Path.of(outputRoot, config.project().name() + "-" + config.project().version() + ".jar");
    }

    private static String outputRoot(BuildSettings build) {
        String outputRoot = build.outputRoot();
        return outputRoot == null || outputRoot.isBlank() ? "target" : outputRoot;
    }

    private static List<Path> files(Path directory, String suffix) {
        return files(directory).stream()
                .filter(path -> path.toString().endsWith(suffix))
                .toList();
    }

    private static List<Path> namedFiles(Path directory, String name) {
        return files(directory).stream()
                .filter(path -> path.getFileName().toString().equals(name))
                .toList();
    }

    private static List<Path> files(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private static SpringBootNativeAotFreshness aotFreshness(
            Path root,
            ProjectConfig config,
            List<Path> generatedSources,
            List<Path> generatedClasses,
            List<Path> reflectionMetadata,
            List<Path> reachabilityMetadata) {
        List<Path> aotFiles = new ArrayList<>();
        aotFiles.addAll(generatedSources);
        aotFiles.addAll(generatedClasses);
        aotFiles.addAll(reflectionMetadata);
        aotFiles.addAll(reachabilityMetadata);
        if (aotFiles.isEmpty()) {
            return new SpringBootNativeAotFreshness("missing", false);
        }
        Optional<FileTime> oldestAot = oldestTime(aotFiles);
        Optional<FileTime> newestInput = newestTime(List.of(
                root.resolve("zolt.toml"),
                root.resolve(config.build().output())));
        if (oldestAot.isPresent()
                && newestInput.isPresent()
                && oldestAot.orElseThrow().compareTo(newestInput.orElseThrow()) < 0) {
            return new SpringBootNativeAotFreshness("stale", true);
        }
        return new SpringBootNativeAotFreshness("present", false);
    }

    private static Optional<FileTime> oldestTime(List<Path> paths) {
        return paths.stream()
                .map(SpringBootNativePlanStateFactory::lastModified)
                .flatMap(Optional::stream)
                .min(FileTime::compareTo);
    }

    private static Optional<FileTime> newestTime(List<Path> paths) {
        return paths.stream()
                .flatMap(path -> Files.isDirectory(path) ? files(path).stream() : Stream.of(path))
                .map(SpringBootNativePlanStateFactory::lastModified)
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

    private static boolean nativeImageAvailable(Path root, Path executable) {
        if (hasPathSeparator(executable) || executable.isAbsolute()) {
            Path path = executable.isAbsolute() ? executable : root.resolve(executable).normalize();
            return Files.isRegularFile(path) && Files.isExecutable(path);
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).resolve(executable);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPathSeparator(Path path) {
        String value = path.toString();
        return value.contains("/") || value.contains("\\") || path.getNameCount() > 1;
    }
}
