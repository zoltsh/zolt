package sh.zolt.maven.metadata;

/** Raised when a cached version listing cannot be written to the metadata cache namespace. */
public final class MetadataCacheException extends RuntimeException {
    public MetadataCacheException(String message) {
        super(message);
    }

    public MetadataCacheException(String message, Throwable cause) {
        super(message, cause);
    }
}
