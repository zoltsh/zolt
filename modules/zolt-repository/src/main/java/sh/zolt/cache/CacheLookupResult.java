package sh.zolt.cache;

import java.util.Objects;

/** A materialized artifact plus the cache outcome that produced it. */
public record CacheLookupResult(CachedArtifact artifact, CacheOutcome outcome) {
    public CacheLookupResult {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(outcome, "outcome");
    }
}
