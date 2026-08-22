package sh.zolt.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarOutputStream;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.lockfile.toml.ZoltLockfileWriter;

/** Adds content-addressed artifacts to current-schema runtime test fixtures. */
public final class TestContentAddressedLockSupport {
    private TestContentAddressedLockSupport() {}

    public static void write(Path projectDirectory, String fixture) throws IOException {
        Path cacheRoot = projectDirectory.resolve("cache");
        ZoltLockfile legacy = new ZoltLockfileReader().read(fixture);
        List<LockPackage> packages = new ArrayList<>();
        for (LockPackage lockPackage : legacy.packages()) {
            Artifact jar = migrate(cacheRoot, lockPackage.jar());
            Artifact pom = migrate(cacheRoot, lockPackage.pom());
            Artifact secondary = migrate(cacheRoot, lockPackage.artifact());
            packages.add(new LockPackage(
                    lockPackage.packageId(),
                    lockPackage.version(),
                    lockPackage.source(),
                    lockPackage.scope(),
                    lockPackage.direct(),
                    jar.path(),
                    pom.path(),
                    jar.sha256(),
                    pom.sha256(),
                    secondary.path(),
                    lockPackage.artifactType(),
                    secondary.sha256(),
                    lockPackage.workspace(),
                    lockPackage.workspaceOutput(),
                    lockPackage.dependencies(),
                    lockPackage.members(),
                    lockPackage.exportedBy(),
                    lockPackage.policies(),
                    lockPackage.toolGroups()));
        }
        new ZoltLockfileWriter().write(projectDirectory.resolve("zolt.lock"), new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                legacy.aliasFingerprint(),
                legacy.projectResolutionFingerprint(),
                legacy.projectResolutionInputFingerprints(),
                packages,
                legacy.conflicts(),
                legacy.policyEffects(),
                legacy.memberGraphs(),
                legacy.workspaceResolutionInputFingerprint(),
                legacy.dependencyRoots()));
    }

    public static Path cachedJar(Path projectDirectory, String coordinate) {
        Path cacheRoot = projectDirectory.resolve("cache");
        return new ZoltLockfileReader().read(projectDirectory.resolve("zolt.lock")).packages().stream()
                .filter(lockPackage -> lockPackage.packageId().toString().equals(coordinate))
                .findFirst()
                .flatMap(LockPackage::jar)
                .map(cacheRoot::resolve)
                .orElseThrow();
    }

    private static Artifact migrate(Path cacheRoot, Optional<String> path) throws IOException {
        if (path.isEmpty()) {
            return Artifact.empty();
        }
        String legacyPath = path.orElseThrow();
        Path source = cacheRoot.resolve(legacyPath);
        if (!Files.isRegularFile(source)) {
            createFixtureArtifact(source);
        }
        String sha256 = sha256(Files.readAllBytes(source));
        String relative = "blobs/v2/sha256/"
                + sha256
                + "/"
                + source.getFileName();
        Path target = cacheRoot.resolve(relative);
        if (!source.equals(target)) {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            preserveLegacyMutationPath(source, target);
        }
        return new Artifact(Optional.of(relative), Optional.of(sha256));
    }

    private static void createFixtureArtifact(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        if (path.getFileName().toString().endsWith(".jar")) {
            try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(path))) {
                // A valid empty archive is safe on javac and test-runner classpaths.
            }
        } else {
            Files.write(path, new byte[0]);
        }
    }

    private static void preserveLegacyMutationPath(Path source, Path target) throws IOException {
        try {
            Files.delete(source);
            Files.createLink(source, target);
        } catch (UnsupportedOperationException | IOException exception) {
            Files.copy(target, source, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Artifact(Optional<String> path, Optional<String> sha256) {
        private static Artifact empty() {
            return new Artifact(Optional.empty(), Optional.empty());
        }
    }
}
