package sh.zolt.build.packageplan;

import sh.zolt.build.PackageException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class PackageCanonicalHash {
    private final MessageDigest digest = sha256();

    void value(String key, String value) {
        update(key);
        update(value == null ? "" : value);
    }

    void bytes(String key, byte[] value) {
        update(key);
        update(value == null ? new byte[0] : value);
    }

    /**
     * Starts a byte field of known length so its content can be streamed in chunks.
     *
     * <p>Produces the same digest as {@link #bytes(String, byte[])} over the same bytes, which is
     * what lets oversized package inputs be hashed without being materialised.
     */
    void beginBytes(String key, long length) {
        update(key);
        digest.update(Long.toString(length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
    }

    void bytesChunk(byte[] buffer, int offset, int length) {
        digest.update(buffer, offset, length);
    }

    void endBytes() {
        digest.update((byte) '\n');
    }

    String finish() {
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private void update(String value) {
        update(value.getBytes(StandardCharsets.UTF_8));
    }

    private void update(byte[] bytes) {
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) '\n');
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new PackageException(
                    "Could not fingerprint package inputs because SHA-256 is unavailable.",
                    exception);
        }
    }
}
