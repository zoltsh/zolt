package sh.zolt.build.packageplan;

import sh.zolt.build.PackageException;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Canonical content fingerprints shared by package planning and archive assembly.
 */
public final class PackageInputFingerprinting {
    static final String CONTENT_SCHEMA = "zolt.package-content.v1";

    private static final Set<String> LOCAL_BUILD_STATE = Set.of(
            ".zolt-build-main.fingerprint",
            ".zolt-build-main.fingerprint.state",
            ".zolt-build-test.fingerprint",
            ".zolt-build-test.fingerprint.state",
            ".zolt-incremental-main.state",
            ".zolt-incremental-test.state");

    private PackageInputFingerprinting() {
    }

    /**
     * Files that archive assemblers consume from a compiled output directory.
     */
    public static List<Path> applicationFiles(Path directory) throws IOException {
        return regularFiles(directory, path -> !LOCAL_BUILD_STATE.contains(path.getFileName().toString()));
    }

    /**
     * Application files paired with the size the directory walk already observed.
     *
     * <p>The walk reads each file's attributes once, so callers get the size without a second stat.
     */
    static List<SizedFile> sizedApplicationFiles(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<SizedFile> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()
                        && !LOCAL_BUILD_STATE.contains(file.getFileName().toString())) {
                    files.add(new SizedFile(
                            root.relativize(file).toString().replace('\\', '/'),
                            file,
                            attributes.size()));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(SizedFile::name));
        return List.copyOf(files);
    }

    record SizedFile(String name, Path path, long size) {
    }

    public static String applicationOutputFingerprint(Path directory) {
        return PackageInputSnapshot
                .of(directory, PackageInputBudget.streaming())
                .fingerprint();
    }

    public static String directoryFingerprint(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            return "missing";
        }
        try {
            return fingerprint(normalized, regularFiles(normalized, ignored -> true));
        } catch (IOException exception) {
            throw unreadable("directory", normalized, exception);
        }
    }

    /**
     * Fingerprints a packaged directory output while excluding its adjacent evidence sidecar.
     */
    public static String packageLayoutFingerprint(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            return "missing";
        }
        try {
            return fingerprint(
                    normalized,
                    regularFiles(
                            normalized,
                            path -> !path.getFileName()
                                    .toString()
                                    .endsWith(".zolt-package.json")));
        } catch (IOException exception) {
            throw unreadable("package layout", normalized, exception);
        }
    }

    public static List<Path> regularFiles(Path directory) throws IOException {
        return regularFiles(directory, ignored -> true);
    }

    private static List<Path> regularFiles(
            Path directory,
            Predicate<Path> include) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var stream = Files.walk(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(include)
                    .sorted(Comparator.comparing(path -> entryName(directory, path)))
                    .toList();
        }
    }

    private static String fingerprint(Path root, List<Path> files) throws IOException {
        PackageCanonicalHash hash = new PackageCanonicalHash();
        hash.value("schema", CONTENT_SCHEMA);
        List<Path> normalized = new ArrayList<>(files.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList());
        normalized.sort(Comparator.comparing(path -> entryName(root, path)));
        for (Path file : normalized) {
            hash.value("path", entryName(root, file));
            hash.bytes("bytes", Files.readAllBytes(file));
        }
        return hash.finish();
    }

    static String entryName(Path root, Path file) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();
        return normalizedFile.startsWith(normalizedRoot)
                ? normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/')
                : normalizedFile.toString().replace('\\', '/');
    }

    private static PackageException unreadable(
            String description,
            Path path,
            IOException exception) {
        return new PackageException(
                "Could not fingerprint package "
                        + description
                        + " at "
                        + path
                        + ". Check that it is readable and retry.",
                exception);
    }
}
