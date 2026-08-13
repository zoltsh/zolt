package sh.zolt.cache;

public class ArtifactCacheException extends RuntimeException {
    public ArtifactCacheException(String message) {
        super(message);
    }

    public ArtifactCacheException(String message, Throwable cause) {
        super(message, cause);
    }
}
