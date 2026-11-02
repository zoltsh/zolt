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
 * INPUTS (config, sources, classpath, generated sources, exec outputs) with EXPECTED-OUTPUT
 * sections {@code [expectedClasses]} and {@code [compilerOutputHashes]}. These inventory every
 * compiler-owned file, including processor metadata, and verify its contents. The skip gate needs
 * both halves; a build-output cache key must use only inputs because the cache restores outputs.
 * Section-aware extraction keeps the key independent of output existence, content, and ordering.
 */
final class BuildFingerprintInputs {
    private static final java.util.Set<String> OUTPUT_SECTIONS = java.util.Set.of(
            "[expectedClasses]", "[compilerOutputHashes]");

    private BuildFingerprintInputs() {
    }

    /** The fingerprint text with output-only sections removed. */
    static String inputsOnly(String fingerprint) {
        StringBuilder inputs = new StringBuilder(fingerprint.length());
        boolean droppingExpectedClasses = false;
        for (String line : fingerprint.lines().toList()) {
            if (isSectionHeader(line)) {
                droppingExpectedClasses = OUTPUT_SECTIONS.contains(line);
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
