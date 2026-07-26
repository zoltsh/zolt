package sh.zolt.build.packaging;

import sh.zolt.build.PackageException;
import sh.zolt.build.packageplan.PackageInputFingerprinting;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;

/**
 * Turns workspace output directories into reusable deterministic thin JARs before nested-archive assembly.
 */
public final class PackageRuntimeJarMaterializer {
    private static final String CACHE_SCHEMA = "zolt.package-runtime-input-cache.v1";

    public Result materialize(
            Path projectDirectory,
            ProjectConfig config,
            List<PackageRuntimeJar> runtimeJars) {
        Path stagingDirectory = ProjectPaths.output(
                projectDirectory,
                "package runtime input staging",
                config.build().outputRoot() + "/zolt-package/runtime-inputs");
        List<PackageRuntimeJar> resolved = new ArrayList<>();
        List<PackageMaterializedInput> materialized = new ArrayList<>();
        for (PackageRuntimeJar runtimeJar : runtimeJars) {
            if (!Files.isDirectory(runtimeJar.jarPath())) {
                resolved.add(runtimeJar);
                continue;
            }
            PackageMaterializedInput input = materialize(stagingDirectory, runtimeJar);
            resolved.add(new PackageRuntimeJar(
                    runtimeJar.packageId(),
                    runtimeJar.version(),
                    input.jarPath()));
            materialized.add(input);
        }
        return new Result(resolved, materialized);
    }

    private static PackageMaterializedInput materialize(
            Path stagingDirectory,
            PackageRuntimeJar runtimeJar) {
        Path sourceDirectory = runtimeJar.jarPath().toAbsolutePath().normalize();
        Path jarPath = stagingDirectory.resolve(
                PackageRuntimeJars.canonicalNestedJarName(runtimeJar));
        Path cacheManifestPath =
                jarPath.resolveSibling(jarPath.getFileName() + ".zolt-cache");
        try {
            List<Path> files =
                    PackageInputFingerprinting.applicationFiles(sourceDirectory);
            String sourceFingerprint =
                    PackageInputFingerprinting.applicationOutputFingerprint(
                            sourceDirectory);
            Files.createDirectories(stagingDirectory);
            Optional<CacheManifest> cached = readCacheManifest(cacheManifestPath);
            String jarSha256 = cached
                    .filter(manifest -> manifest.sourceFingerprint()
                            .equals(sourceFingerprint))
                    .filter(manifest -> Files.isRegularFile(jarPath))
                    .filter(manifest -> manifest.jarSha256()
                            .equals(readableJarSha256(jarPath)))
                    .map(CacheManifest::jarSha256)
                    .orElseGet(() -> rebuild(
                            sourceDirectory,
                            files,
                            jarPath,
                            cacheManifestPath,
                            sourceFingerprint));
            if (!validJar(jarPath)) {
                jarSha256 = rebuild(
                        sourceDirectory,
                        files,
                        jarPath,
                        cacheManifestPath,
                        sourceFingerprint);
            }
            return new PackageMaterializedInput(
                    runtimeJar.packageId() + ":" + runtimeJar.version(),
                    sourceDirectory,
                    jarPath,
                    sourceFingerprint,
                    jarSha256);
        } catch (IOException exception) {
            throw new PackageException(
                    "Could not materialize workspace runtime input `"
                            + runtimeJar.packageId()
                            + "` from "
                            + sourceDirectory
                            + ". Check that the workspace build output is readable and target/ is writable.",
                    exception);
        }
    }

    private static String rebuild(
            Path sourceDirectory,
            List<Path> files,
            Path jarPath,
            Path cacheManifestPath,
            String sourceFingerprint) {
        Path temporaryJar = null;
        Path temporaryManifest = null;
        try {
            Path parent = jarPath.getParent();
            temporaryJar = Files.createTempFile(
                    parent,
                    jarPath.getFileName() + ".",
                    ".tmp");
            PackageArchiveWriter.writeJarFromFiles(
                    temporaryJar,
                    sourceDirectory,
                    files);
            validateJar(temporaryJar);
            String jarSha256 = fileSha256(temporaryJar);
            atomicReplace(temporaryJar, jarPath);
            temporaryJar = null;

            temporaryManifest = Files.createTempFile(
                    parent,
                    cacheManifestPath.getFileName() + ".",
                    ".tmp");
            Files.writeString(
                    temporaryManifest,
                    new CacheManifest(sourceFingerprint, jarSha256).encode(),
                    StandardCharsets.UTF_8);
            atomicReplace(temporaryManifest, cacheManifestPath);
            temporaryManifest = null;
            return jarSha256;
        } catch (IOException exception) {
            throw new PackageException(
                    "Could not transactionally materialize workspace runtime input at "
                            + jarPath
                            + ". Check that the staging directory supports atomic file replacement.",
                    exception);
        } finally {
            deleteTemporary(temporaryJar);
            deleteTemporary(temporaryManifest);
        }
    }

    public static String directoryFingerprint(Path directory) {
        return PackageInputFingerprinting.applicationOutputFingerprint(directory);
    }

    private static Optional<CacheManifest> readCacheManifest(Path path)
            throws IOException {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() != 3
                || !("schema=" + CACHE_SCHEMA).equals(lines.get(0))
                || !lines.get(1).startsWith("sourceFingerprint=")
                || !lines.get(2).startsWith("jarSha256=")) {
            return Optional.empty();
        }
        String sourceFingerprint =
                lines.get(1).substring("sourceFingerprint=".length());
        String jarSha256 = lines.get(2).substring("jarSha256=".length());
        if (sourceFingerprint.isBlank() || jarSha256.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new CacheManifest(sourceFingerprint, jarSha256));
    }

    private static String readableJarSha256(Path jarPath) {
        try {
            if (!validJar(jarPath)) {
                return "invalid";
            }
            return fileSha256(jarPath);
        } catch (IOException exception) {
            return "invalid";
        }
    }

    private static String fileSha256(Path path) throws IOException {
        return "sha256:" + HexFormat.of().formatHex(
                sha256().digest(Files.readAllBytes(path)));
    }

    private static boolean validJar(Path path) {
        try {
            validateJar(path);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static void validateJar(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            for (var entry : jar.stream()
                    .filter(candidate -> !candidate.isDirectory())
                    .toList()) {
                try (var input = jar.getInputStream(entry)) {
                    input.transferTo(java.io.OutputStream.nullOutputStream());
                }
            }
        }
    }

    private static void atomicReplace(Path source, Path target)
            throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException(
                    "Atomic replacement is not supported for " + target,
                    exception);
        }
    }

    private static void deleteTemporary(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The primary materialization failure is more actionable than temporary cleanup.
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new PackageException(
                    "Could not materialize workspace runtime input because SHA-256 is unavailable.",
                    exception);
        }
    }

    private record CacheManifest(
            String sourceFingerprint,
            String jarSha256) {
        String encode() {
            return "schema="
                    + CACHE_SCHEMA
                    + "\nsourceFingerprint="
                    + sourceFingerprint
                    + "\njarSha256="
                    + jarSha256
                    + "\n";
        }
    }

    public record Result(
            List<PackageRuntimeJar> runtimeJars,
            List<PackageMaterializedInput> materializedInputs) {
        public Result {
            runtimeJars = List.copyOf(runtimeJars);
            materializedInputs = List.copyOf(materializedInputs);
        }
    }
}
