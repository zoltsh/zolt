package sh.zolt.toolchain.install;

import sh.zolt.error.ActionableException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class JavaToolchainChecksum {
    private JavaToolchainChecksum() {
    }

    static void verifySha256(Path archive, String expected) {
        String actual = sha256(archive);
        if (!actual.equalsIgnoreCase(expected.strip())) {
            throw new ActionableException(
                    "Java toolchain artifact checksum did not match.",
                    "Expected sha256:" + expected.strip() + " but downloaded sha256:" + actual
                            + ". Remove the partial download and retry `zolt toolchain sync`.");
        }
    }

    private static String sha256(Path archive) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(archive)) {
                byte[] buffer = new byte[8192];
                int read = input.read(buffer);
                while (read >= 0) {
                    digest.update(buffer, 0, read);
                    read = input.read(buffer);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new ActionableException(
                    "Could not read downloaded Java toolchain artifact at " + archive + ".",
                    "Check that the toolchain cache is readable and retry `zolt toolchain sync`.");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
