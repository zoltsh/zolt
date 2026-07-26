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
