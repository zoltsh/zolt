package sh.zolt.workspace.state;

import sh.zolt.build.BuildException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class WorkspaceHash {
    private WorkspaceHash() {
    }

    public static String text(String value) {
        return bytes(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String bytes(byte[] value) {
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
