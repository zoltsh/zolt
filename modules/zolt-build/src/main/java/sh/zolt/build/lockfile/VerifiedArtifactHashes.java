package sh.zolt.build.lockfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Process-local bridge from lockfile integrity verification to downstream content fingerprints.
 *
 * <p>The lockfile verifier remains the trust boundary: it always hashes artifacts for each command
 * invocation. Once that succeeds, build fingerprints can reuse the exact SHA-256 instead of reading
 * the same JARs again. File metadata fences the short-lived reuse against ordinary replacement or
 * mutation between verification and fingerprinting.
 */
public final class VerifiedArtifactHashes {
    private static final ConcurrentMap<Path, VerifiedArtifactHash> HASHES = new ConcurrentHashMap<>();

    private VerifiedArtifactHashes() {
    }

    public static Optional<String> currentHash(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        VerifiedArtifactHash verified = HASHES.get(normalized);
        if (verified == null) {
            return Optional.empty();
        }
        if (verified.isCurrent()) {
            return Optional.of(verified.hash());
        }
        HASHES.remove(normalized, verified);
        return Optional.empty();
    }

    static void record(Path path, String hash) {
        Path normalized = path.toAbsolutePath().normalize();
        try {
            BasicFileAttributes attributes = Files.readAttributes(normalized, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                HASHES.remove(normalized);
                return;
            }
            HASHES.put(
                    normalized,
                    new VerifiedArtifactHash(
                            normalized,
                            attributes.fileKey(),
                            attributes.size(),
                            attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                            hash));
        } catch (IOException exception) {
            HASHES.remove(normalized);
        }
    }

    static void invalidate(Path path) {
        HASHES.remove(path.toAbsolutePath().normalize());
    }

    static void clear() {
        HASHES.clear();
    }

    private record VerifiedArtifactHash(
            Path path,
            Object fileKey,
            long size,
            long lastModifiedNanos,
            String hash) {
        private boolean isCurrent() {
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                return attributes.isRegularFile()
                        && Objects.equals(attributes.fileKey(), fileKey)
                        && attributes.size() == size
                        && attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS) == lastModifiedNanos;
            } catch (IOException exception) {
                return false;
            }
        }
    }
}
