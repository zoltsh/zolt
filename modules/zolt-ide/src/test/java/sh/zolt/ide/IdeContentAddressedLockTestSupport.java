package sh.zolt.ide;

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
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.lockfile.toml.ZoltLockfileWriter;

final class IdeContentAddressedLockTestSupport {
    private IdeContentAddressedLockTestSupport() {}

    static void write(Path lockfilePath, Path cacheRoot, String fixture) throws IOException {
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
        new ZoltLockfileWriter().write(lockfilePath, new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                legacy.aliasFingerprint(),
                legacy.projectResolutionFingerprint(),
                legacy.projectResolutionInputFingerprints(),
                packages,
                legacy.conflicts(),
                legacy.policyEffects(),
                legacy.memberGraphs(),
                legacy.workspaceResolutionInputFingerprint()));
    }

    static Path cachedJar(Path lockfilePath, Path cacheRoot, String coordinate) {
        return new ZoltLockfileReader().read(lockfilePath).packages().stream()
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
        byte[] bytes = Files.isRegularFile(source)
                ? Files.readAllBytes(source)
                : legacyPath.getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256(bytes);
        String relative = "blobs/v2/sha256/"
                + sha256
                + "/"
                + Path.of(legacyPath).getFileName();
        Path target = cacheRoot.resolve(relative);
        Files.createDirectories(target.getParent());
        if (Files.isRegularFile(source) && !source.equals(target)) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } else if (!Files.isRegularFile(target)) {
            Files.write(target, bytes);
        }
        return new Artifact(Optional.of(relative), Optional.of(sha256));
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
