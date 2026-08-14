package sh.zolt.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

/** Migrates concise workspace test fixtures onto the version 6 cache-path contract. */
public final class WorkspaceContentAddressedLockTestSupport {
    private WorkspaceContentAddressedLockTestSupport() {}

    public static void write(Path lockfilePath, Path cacheRoot, String fixture) throws IOException {
        Files.createDirectories(lockfilePath.toAbsolutePath().normalize().getParent());
        new ZoltLockfileWriter().write(lockfilePath, migrate(cacheRoot, fixture));
    }

    public static ZoltLockfile migrate(Path cacheRoot, String fixture) throws IOException {
        return migrate(cacheRoot, new ZoltLockfileReader().read(fixture));
    }

    public static ZoltLockfile migrate(Path cacheRoot, ZoltLockfile legacy) throws IOException {
        List<LockPackage> packages = new ArrayList<>();
        for (LockPackage lockPackage : legacy.packages()) {
            Artifact jar = migrateArtifact(cacheRoot, lockPackage.jar());
            Artifact pom = migrateArtifact(cacheRoot, lockPackage.pom());
            Artifact secondary = migrateArtifact(cacheRoot, lockPackage.artifact());
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
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                legacy.aliasFingerprint(),
                legacy.projectResolutionFingerprint(),
                legacy.projectResolutionInputFingerprints(),
                packages,
                legacy.conflicts(),
                legacy.policyEffects(),
                legacy.memberGraphs(),
                legacy.workspaceResolutionInputFingerprint());
    }

    public static Path cachedArtifact(Path cacheRoot, Path legacyArtifact) throws IOException {
        return cacheRoot.resolve(contentAddressedPath(legacyArtifact));
    }

    public static void writeArtifact(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    public static String sha256(String content) {
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    private static Artifact migrateArtifact(Path cacheRoot, Optional<String> path) throws IOException {
        if (path.isEmpty()) {
            return Artifact.empty();
        }
        Path source = cacheRoot.resolve(path.orElseThrow());
        if (!Files.isRegularFile(source)) {
            createFixtureArtifact(source);
        }
        String relative = contentAddressedPath(source);
        Path target = cacheRoot.resolve(relative);
        if (!source.equals(target)) {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            preserveLegacyMutationPath(source, target);
        }
        return new Artifact(Optional.of(relative), Optional.of(sha256(Files.readAllBytes(target))));
    }

    private static String contentAddressedPath(Path artifact) throws IOException {
        String digest = sha256(Files.readAllBytes(artifact));
        return "blobs/v2/sha256/" + digest + "/" + artifact.getFileName();
    }

    private static void createFixtureArtifact(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        if (path.getFileName().toString().endsWith(".jar")) {
            try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(path))) {
                // A valid empty archive is safe on compiler and test-runner classpaths.
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
