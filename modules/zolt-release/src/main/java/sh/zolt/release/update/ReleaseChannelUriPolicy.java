package sh.zolt.release.update;

import java.net.URI;

final class ReleaseChannelUriPolicy {
    private ReleaseChannelUriPolicy() {
    }

    static void validate(URI uri, boolean allowLocalFile) {
        String scheme = uri.getScheme();
        if (scheme == null || scheme.isBlank()) {
            throw invalid("must use HTTPS.");
        }
        if (uri.getUserInfo() != null) {
            throw invalid("must not include URL credentials.");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw invalid("must not include a query or fragment.");
        }
        if ("file".equalsIgnoreCase(scheme)) {
            validateLocalFile(uri, allowLocalFile);
            return;
        }
        if (!"https".equalsIgnoreCase(scheme) || uri.getHost() == null || uri.getHost().isBlank()) {
            throw invalid("must be an HTTPS URL with a host.");
        }
    }

    static boolean isLocalFile(URI uri) {
        return "file".equalsIgnoreCase(uri.getScheme());
    }

    static void requireChannelDocument(
            URI uri, String collection, String channel) {
        if (isLocalFile(uri)) {
            return;
        }
        String movingSuffix = "/" + collection + "/" + channel + ".json";
        String snapshotSuffix = switch (collection) {
            case "channels" -> "/channel-" + channel + ".json";
            case "releases" -> "/release-index-" + channel + ".json";
            default -> throw invalid("uses an unsupported release document collection.");
        };
        String path = uri.getPath();
        if (path == null
                || (!path.endsWith(movingSuffix) && !path.endsWith(snapshotSuffix))) {
            throw invalid("must end with `"
                    + movingSuffix
                    + "` or `"
                    + snapshotSuffix
                    + "` for channel `"
                    + channel
                    + "`.");
        }
    }

    private static void validateLocalFile(URI uri, boolean allowLocalFile) {
        if (!allowLocalFile) {
            throw invalid("may use file: only for explicit local development or test manifests.");
        }
        if (uri.getAuthority() != null && !uri.getAuthority().isBlank()) {
            throw invalid("file: manifests must be local paths without an authority.");
        }
        if (uri.getPath() == null || uri.getPath().isBlank()) {
            throw invalid("file: manifests must include a local path.");
        }
    }

    private static NativeUpdateException invalid(String detail) {
        return new NativeUpdateException("Release channel URL " + detail);
    }
}
