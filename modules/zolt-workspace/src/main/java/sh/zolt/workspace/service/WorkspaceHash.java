package sh.zolt.workspace.service;

import sh.zolt.build.BuildException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class WorkspaceHash {
    private WorkspaceHash() {
    }

    static String text(String value) {
        return bytes(value.getBytes(StandardCharsets.UTF_8));
    }

    static String bytes(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new BuildException(
                    "Could not compute workspace state because SHA-256 is unavailable.",
                    exception);
        }
    }
}
