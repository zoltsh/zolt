package sh.zolt.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Stable, filesystem-safe identity for one ordered repository configuration. */
public record RepositoryCacheScope(String key) {
    private static final int SHA_256_HEX_LENGTH = 64;

    public RepositoryCacheScope {
        Objects.requireNonNull(key, "key");
        if (key.length() != SHA_256_HEX_LENGTH || !key.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Repository cache scope must be a lowercase SHA-256 value.");
        }
    }

    public static RepositoryCacheScope of(String repositoryConfigurationIdentity) {
        Objects.requireNonNull(repositoryConfigurationIdentity, "repositoryConfigurationIdentity");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(repositoryConfigurationIdentity.getBytes(StandardCharsets.UTF_8));
            return new RepositoryCacheScope(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new ArtifactCacheException(
                    "Could not identify the repository cache scope because SHA-256 is unavailable.",
                    exception);
        }
    }
}
