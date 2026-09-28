package sh.zolt.build.compile;

import sh.zolt.build.BuildException;
import sh.zolt.doctor.JdkStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

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

    /** Composes the selected JDK and the relocatable identity of a verified Groovy compiler. */
    public static String of(JdkStatus status, GroovyCompilerToolchain groovyToolchain) {
        Objects.requireNonNull(groovyToolchain, "Groovy compiler toolchain is required.");
        return of(status, "groovyCompiler", groovyToolchain.identity());
    }

    /** Composes the selected JDK with one explicitly labelled, relocatable compiler identity. */
    public static String of(
            JdkStatus status,
            String compilerLabel,
            String compilerIdentity) {
        return of(status, compilerLabel, compilerIdentity, "");
    }

    /**
     * Composes a compiler identity with an optional semantics token. The token is included only when
     * non-blank, so existing Java-only and Groovy identities remain byte-for-byte stable.
     */
    public static String of(
            JdkStatus status,
            String compilerLabel,
            String compilerIdentity,
            String semanticsToken) {
        String label = requireSingleLine(compilerLabel, "compiler label");
        if (label.indexOf('=') >= 0) {
            throw new IllegalArgumentException("Compiler identity label must not contain `=`.");
        }
        String identity = requireSingleLine(compilerIdentity, "compiler identity");
        String semantics = semanticsToken == null ? "" : semanticsToken.strip();
        if (semantics.indexOf('\n') >= 0 || semantics.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Compiler semantics token must be a single line.");
        }
        String material = String.join(
                "\n",
                "javaCompiler=" + of(status),
                label + "=" + identity);
        if (!semantics.isEmpty()) {
            material += "\ncompilerSemantics=" + semantics;
        }
        return "sha256:" + sha256(material);
    }

    private static String requireSingleLine(String value, String label) {
        Objects.requireNonNull(value, "Compiler " + label + " is required.");
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Compiler " + label + " must not be blank.");
        }
        if (normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Compiler " + label + " must be a single line.");
        }
        return normalized;
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
