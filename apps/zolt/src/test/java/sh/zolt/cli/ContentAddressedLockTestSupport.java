package sh.zolt.cli;

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
import java.util.regex.Pattern;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.lockfile.toml.ZoltLockfileWriter;
import sh.zolt.resolve.fingerprint.ProjectResolutionFingerprint;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import sh.zolt.workspace.resolve.WorkspaceResolutionInputFingerprint;

/** Adds content-addressed artifacts and fingerprints to current-schema CLI test fixtures. */
public final class ContentAddressedLockTestSupport {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private ContentAddressedLockTestSupport() {}

    public static void write(Path lockfilePath, Path cacheRoot, String fixture) throws IOException {
        Files.createDirectories(lockfilePath.toAbsolutePath().normalize().getParent());
        ZoltLockfile migrated = withProjectFingerprint(lockfilePath, migrate(cacheRoot, fixture));
        new ZoltLockfileWriter().write(lockfilePath, withWorkspaceFingerprint(lockfilePath, migrated));
    }

    public static ZoltLockfile migrate(Path cacheRoot, String fixture) throws IOException {
        ZoltLockfile legacy = new ZoltLockfileReader().read(fixture);
        List<LockPackage> packages = new ArrayList<>();
        for (LockPackage lockPackage : legacy.packages()) {
            Artifact jar = migrateArtifact(cacheRoot, lockPackage.jar(), lockPackage.jarSha256());
            Artifact pom = migrateArtifact(cacheRoot, lockPackage.pom(), lockPackage.pomSha256());
            Artifact secondary = migrateArtifact(
                    cacheRoot, lockPackage.artifact(), lockPackage.artifactSha256());
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
                legacy.workspaceResolutionInputFingerprint(),
                legacy.dependencyRoots());
    }

    public static Path cachedJar(Path lockfilePath, Path cacheRoot, String coordinate) {
        ZoltLockfile lockfile = new ZoltLockfileReader().read(lockfilePath);
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().toString().equals(coordinate))
                .findFirst()
                .flatMap(LockPackage::jar)
                .map(cacheRoot::resolve)
                .orElseThrow();
    }

    private static ZoltLockfile withProjectFingerprint(Path lockfilePath, ZoltLockfile lockfile)
            throws IOException {
        Path configPath = lockfilePath.toAbsolutePath().normalize().getParent().resolve("zolt.toml");
        if (lockfile.projectResolutionFingerprint().isPresent() || !Files.isRegularFile(configPath)) {
            return lockfile;
        }
        String config = Files.readString(configPath);
        if (!config.contains("[project]")) {
            return lockfile;
        }
        String fingerprint = ProjectResolutionFingerprint.fingerprint(
                new ManifestProjectLoader().load(configPath.getParent()));
        return new ZoltLockfile(
                lockfile.version(),
                lockfile.aliasFingerprint(),
                Optional.of(fingerprint),
                lockfile.projectResolutionInputFingerprints(),
                lockfile.packages(),
                lockfile.conflicts(),
                lockfile.policyEffects(),
                lockfile.memberGraphs(),
                lockfile.workspaceResolutionInputFingerprint(),
                lockfile.dependencyRoots());
    }

    private static ZoltLockfile withWorkspaceFingerprint(Path lockfilePath, ZoltLockfile lockfile) {
        Path root = lockfilePath.toAbsolutePath().normalize().getParent();
        Path configPath = root.resolve("zolt.toml");
        if (lockfile.workspaceResolutionInputFingerprint().isPresent()
                || !Files.isRegularFile(configPath)) {
            return lockfile;
        }
        String config = readStringUnchecked(configPath);
        if (!config.contains("[workspace")) {
            return lockfile;
        }
        String content = new ZoltLockfileWriter().write(lockfile);
        return new ManifestWorkspaceLoader().discover(root)
                .flatMap(workspace -> WorkspaceResolutionInputFingerprint.fingerprint(workspace, content))
                .map(fingerprint -> lockfile.withWorkspaceResolutionInputFingerprint(Optional.of(fingerprint)))
                .orElse(lockfile);
    }

    private static Artifact migrateArtifact(
            Path cacheRoot,
            Optional<String> path,
            Optional<String> recordedSha256) throws IOException {
        if (path.isEmpty()) {
            return Artifact.empty();
        }
        String legacyPath = path.orElseThrow();
        Path source = cacheRoot.resolve(legacyPath);
        String sha256 = recordedSha256
                .filter(value -> SHA_256.matcher(value).matches())
                .orElseGet(() -> hashUnchecked(Files.isRegularFile(source)
                        ? readUnchecked(source)
                        : legacyPath.getBytes(StandardCharsets.UTF_8)));
        String filename = Path.of(legacyPath).getFileName().toString();
        String relative = "blobs/v2/sha256/" + sha256 + "/" + filename;
        Path target = cacheRoot.resolve(relative);
        if (Files.isRegularFile(source) && !source.equals(target)) {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            preserveLegacyMutationPath(source, target);
        } else if (!Files.isRegularFile(target)) {
            Files.createDirectories(target.getParent());
            Files.write(target, legacyPath.getBytes(StandardCharsets.UTF_8));
        }
        return new Artifact(Optional.of(relative), Optional.of(sha256));
    }

    private static void preserveLegacyMutationPath(Path source, Path target) throws IOException {
        try {
            Files.delete(source);
            Files.createLink(source, target);
        } catch (UnsupportedOperationException | IOException exception) {
            Files.copy(target, source, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] readUnchecked(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read test artifact " + path, exception);
        }
    }

    private static String readStringUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read test config " + path, exception);
        }
    }

    private static String hashUnchecked(byte[] bytes) {
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
