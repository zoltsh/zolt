package sh.zolt.project;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public final class RepositoryUrlPolicy {
    private RepositoryUrlPolicy() {
    }

    public static URI requireSafeUrl(String subject, String url, boolean credentialsConfigured) {
        URI uri = parse(subject, url);
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            throw new IllegalArgumentException(
                    subject
                            + " URL contains embedded credentials. Move credentials to [credentials] environment references and set the repository credentials field instead of putting username, password, or token values in the URL.");
        }
        String scheme = uri.getScheme();
        if (scheme == null || scheme.isBlank()) {
            throw new IllegalArgumentException(subject + " URL must include an https:// or http:// scheme.");
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if (!"https".equals(normalizedScheme) && !"http".equals(normalizedScheme)) {
            throw new IllegalArgumentException(
                    subject + " URL uses unsupported scheme `" + scheme + "`. Use an HTTPS Maven-compatible repository URL.");
        }
        if (credentialsConfigured && !"https".equals(normalizedScheme) && !isLocalHttp(uri)) {
            throw new IllegalArgumentException(
                    subject
                            + " uses credentials with an insecure remote repository URL. Credentialed remote repositories require HTTPS.");
        }
        if ("http".equals(normalizedScheme) && !isLocalHttp(uri)) {
            throw new IllegalArgumentException(
                    subject
                            + " uses non-local HTTP. Use HTTPS for remote repositories; plain HTTP is allowed only for localhost or loopback development repositories.");
        }
        return uri;
    }

    private static URI parse(String subject, String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(subject + " URL must be non-empty.");
        }
        try {
            URI uri = new URI(url.trim());
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException(subject + " URL must include a repository host.");
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(subject + " URL is not a valid URI.", exception);
        }
    }

    private static boolean isLocalHttp(URI uri) {
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalizedHost)
                || "::1".equals(normalizedHost)
                || "0:0:0:0:0:0:0:1".equals(normalizedHost)
                || normalizedHost.startsWith("127.");
    }
}
