package sh.zolt.release.channel;

import java.util.Objects;

public final class ReleaseDistributionUrlLayout {
    public static final String DEFAULT_ORIGIN = "https://dist.zolt.sh";

    private final String origin;

    public ReleaseDistributionUrlLayout() {
        this(DEFAULT_ORIGIN);
    }

    public ReleaseDistributionUrlLayout(String origin) {
        String normalized = Objects.requireNonNull(origin, "origin").strip();
        if (!normalized.startsWith("https://")) {
            throw new ReleaseChannelManifestException("Release distribution origin must use HTTPS.");
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        this.origin = normalized;
    }

    public String origin() {
        return origin;
    }

    public String channelManifestUrl(String channel) {
        return origin + "/channels/" + safePathSegment(channel, "channel") + ".json";
    }

    public String releaseIndexUrl(String channel) {
        return origin + "/releases/" + safePathSegment(channel, "channel") + ".json";
    }

    private static String safePathSegment(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).strip();
        if (normalized.isEmpty()
                || normalized.contains("/")
                || normalized.contains("\\")
                || normalized.contains("..")) {
            throw new ReleaseChannelManifestException("Release distribution " + label + " must be one URL path segment.");
        }
        return normalized;
    }

}
