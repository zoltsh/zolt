package sh.zolt.build.compile;

import sh.zolt.build.BuildException;
import sh.zolt.doctor.JdkStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** A single, opaque identity for the compiler selected for a compile scope. */
public final class EffectiveCompilerIdentity {
    private EffectiveCompilerIdentity() {
    }

    public static String of(JdkStatus status) {
        String material = status.compilerIdentity()
                .filter(value -> !value.isBlank())
                .orElseGet(() -> String.join(
                        "\n",
                        "source=unmanaged",
                        "version=" + status.version().orElse("unknown"),
                        "javaHome=" + status.javaHome().map(Object::toString).orElse("missing"),
                        "javac=" + status.javac().map(Object::toString).orElse("missing"),
                        "os=" + System.getProperty("os.name", "unknown"),
                        "arch=" + System.getProperty("os.arch", "unknown")));
        return "sha256:" + sha256(material);
    }

    private static String sha256(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new BuildException(
                    "Could not identify the effective Java compiler because SHA-256 is unavailable.",
                    exception);
        }
    }
}
