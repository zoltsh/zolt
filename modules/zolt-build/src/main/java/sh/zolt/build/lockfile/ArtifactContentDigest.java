package sh.zolt.build.lockfile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Reads a cached artifact and returns its SHA-256.
 *
 * <p>Shared by {@link VerifiedArtifactIndex} and {@link ArtifactIntegrityVerifier} so the two agree
 * byte-for-byte on what an artifact hashes to while each phrases read failures in its own terms.
 */
final class ArtifactContentDigest {
    private static final int BUFFER_SIZE = 64 * 1024;

    private ArtifactContentDigest() {
    }

    static String sha256(Path artifact) throws IOException {
        try (InputStream input = Files.newInputStream(artifact)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
