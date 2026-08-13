package sh.zolt.cache;

/** A structurally valid cache location whose index or content cannot be trusted. */
final class CorruptArtifactCacheEntryException extends ArtifactCacheException {
    CorruptArtifactCacheEntryException(String message) {
        super(message);
    }

    CorruptArtifactCacheEntryException(String message, Throwable cause) {
        super(message, cause);
    }
}
