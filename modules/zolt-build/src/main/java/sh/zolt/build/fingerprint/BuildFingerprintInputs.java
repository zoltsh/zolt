package sh.zolt.build.fingerprint;

import sh.zolt.build.BuildException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Derives the inputs-only view of a compile fingerprint.
 *
 * <p>The canonical fingerprint text (see {@link BuildFingerprintContent}) intentionally mixes build
 * INPUTS (config, sources, classpath, generated sources, exec outputs) with the single EXPECTED-OUTPUT
 * section {@code [expectedClasses]}, which lists the compiler outputs expected to remain present.
 * This is the source-mapped {@code .class} set for Java/Groovy and the observed {@code .class} plus
 * Kotlin module metadata set for Kotlin. The skip-gate needs both halves; a build-output cache KEY
 * must be derived from the inputs-only half, because the outputs are exactly what the cache restores.
 * Keying on the outputs would be circular.
 *
 * <p>{@code [expectedClasses]} is the sole output section, so the inputs-only text is the fingerprint
 * with that one section removed. The extraction is section-aware rather than a trailing truncation so
 * it stays correct if section ordering ever changes.
 */
final class BuildFingerprintInputs {
    private static final String EXPECTED_CLASSES_SECTION = "[expectedClasses]";

    private BuildFingerprintInputs() {
    }

    /** The fingerprint text with the {@code [expectedClasses]} output-only section removed. */
    static String inputsOnly(String fingerprint) {
        StringBuilder inputs = new StringBuilder(fingerprint.length());
        boolean droppingExpectedClasses = false;
        for (String line : fingerprint.lines().toList()) {
            if (isSectionHeader(line)) {
                droppingExpectedClasses = EXPECTED_CLASSES_SECTION.equals(line);
                if (droppingExpectedClasses) {
                    continue;
                }
            }
            if (droppingExpectedClasses) {
                continue;
            }
            inputs.append(line).append('\n');
        }
        return inputs.toString();
    }

    /** Hex SHA-256 of the inputs-only fingerprint text; the content component of a build-cache key. */
    static String inputsSha256(String fingerprint) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(inputsOnly(fingerprint).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new BuildException("Could not compute build fingerprint because SHA-256 is unavailable.", exception);
        }
    }

    private static boolean isSectionHeader(String line) {
        return line.startsWith("[") && line.endsWith("]");
    }
}
