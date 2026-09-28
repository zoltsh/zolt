package sh.zolt.build.cache;

import sh.zolt.build.BuildException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The content address of a build-output cache entry.
 *
 * <p>The key material folds together everything that determines the compiled bytes: the inputs-only
 * compile fingerprint (see {@code BuildFingerprintInputs}), the compile scope, a cache-format version
 * (so a future archive/layout change can invalidate old entries), and the effective compiler
 * identity. The same identity also participates in the local no-op fingerprint and incremental
 * state; a cache adds it here explicitly so every cache-key format is self-contained.
 */
public record BuildCacheKey(BuildCacheScope scope, String hash) {
    /** Bumped when the archive format, exclusion set, or key derivation changes incompatibly. */
    public static final String FORMAT_VERSION = "2";

    public static BuildCacheKey of(BuildCacheScope scope, String inputsFingerprintSha256, String jdkIdentity) {
        String material = "zolt-build-cache\n"
                + "format=" + FORMAT_VERSION + '\n'
                + "scope=" + scope.id() + '\n'
                + "jdk=" + jdkIdentity + '\n'
                + "inputs=" + inputsFingerprintSha256 + '\n';
        return new BuildCacheKey(scope, sha256Hex(material));
    }

    /** Repository-relative object path for this key, e.g. {@code ab/abcdef...&lt;suffix&gt;}. */
    public String shardedPath(String suffix) {
        return hash.substring(0, 2) + "/" + hash + suffix;
    }

    private static String sha256Hex(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new BuildException("Could not compute build cache key because SHA-256 is unavailable.", exception);
        }
    }
}
