package sh.zolt.release.channel;

public final class ReleaseChannelManifestException extends RuntimeException {
    public ReleaseChannelManifestException(String message) {
        super(message);
    }

    public ReleaseChannelManifestException(String message, Throwable cause) {
        super(message, cause);
    }
}
