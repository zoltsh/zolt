package sh.zolt.resolve.materialization;

import java.util.Objects;

/**
 * An artifact that is in the cache, described by what a lock records about it: where it sits under
 * the cache root, and the digest of its bytes.
 *
 * <p>Lockfile assembly never needs the bytes themselves — it needs this. Saying so lets a session
 * remember the answer for an artifact many projects select, instead of re-reading and re-hashing the
 * file once per project, without holding every jar of a large workspace in memory to do it.
 */
public record MaterializedArtifact(String repositoryPath, String sha256) {
    public MaterializedArtifact {
        Objects.requireNonNull(repositoryPath, "repositoryPath");
        Objects.requireNonNull(sha256, "sha256");
    }
}
