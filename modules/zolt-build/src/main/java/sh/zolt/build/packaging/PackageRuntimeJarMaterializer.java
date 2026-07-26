package sh.zolt.build.packaging;

import sh.zolt.build.PackageException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Turns workspace output directories into reusable deterministic thin JARs before nested-archive assembly.
 */
public final class PackageRuntimeJarMaterializer {
    private static final Set<String> LOCAL_BUILD_FINGERPRINTS = Set.of(
            ".zolt-build-main.fingerprint",
            ".zolt-build-main.fingerprint.state",
            ".zolt-build-test.fingerprint",
            ".zolt-build-test.fingerprint.state",
            ".zolt-incremental-main.state",
            ".zolt-incremental-test.state");

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
        Path fingerprintPath = jarPath.resolveSibling(jarPath.getFileName() + ".sha256");
        try {
            List<Path> files = files(sourceDirectory);
            String sourceFingerprint = sourceFingerprint(sourceDirectory, files);
            Files.createDirectories(stagingDirectory);
            if (!Files.isRegularFile(jarPath)
                    || !Files.isRegularFile(fingerprintPath)
                    || !Files.readString(fingerprintPath, StandardCharsets.UTF_8).trim().equals(sourceFingerprint)) {
                PackageArchiveWriter.writeJarFromFiles(jarPath, sourceDirectory, files);
                Files.writeString(
                        fingerprintPath,
                        sourceFingerprint + "\n",
                        StandardCharsets.UTF_8);
            }
            return new PackageMaterializedInput(
                    runtimeJar.packageId() + ":" + runtimeJar.version(),
                    sourceDirectory,
                    jarPath,
                    sourceFingerprint,
                    fileSha256(jarPath));
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

    private static List<Path> files(Path directory) throws IOException {
        try (var stream = Files.walk(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !LOCAL_BUILD_FINGERPRINTS.contains(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> entryName(directory, path)))
                    .toList();
        }
    }

    public static String directoryFingerprint(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            return "missing";
        }
        try {
            return sourceFingerprint(normalized, files(normalized));
        } catch (IOException exception) {
            throw new PackageException(
                    "Could not fingerprint materialized package input source at "
                            + normalized
                            + ". Check that the directory is readable and retry.",
                    exception);
        }
    }

    private static String sourceFingerprint(Path directory, List<Path> files) throws IOException {
        MessageDigest digest = sha256();
        for (Path file : files) {
            digest.update(entryName(directory, file).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(file));
            digest.update((byte) 0);
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private static String fileSha256(Path path) throws IOException {
        return "sha256:" + HexFormat.of().formatHex(
                sha256().digest(Files.readAllBytes(path)));
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

    private static String entryName(Path root, Path file) {
        return root.relativize(file).normalize().toString().replace('\\', '/');
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
