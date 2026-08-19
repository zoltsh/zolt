package sh.zolt.manifest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/** A safe absolute HTTP(S) repository URL retaining its authored spelling. */
public record RepositoryUrl(String value) {
    public RepositoryUrl {
        Objects.requireNonNull(value, "Repository URL must not be null.");
        URI uri = parse(value);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw invalid(value, "use an absolute HTTP(S) URL");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw invalid(value, "include a host");
        }
        if (uri.getRawUserInfo() != null) {
            throw invalid(value, "remove embedded user information and reference a credential ID instead");
        }
        if (uri.getRawFragment() != null) {
            throw invalid(value, "remove the fragment");
        }
        if (scheme.equals("http") && !isLoopbackHost(uri.getHost())) {
            throw invalid(value, "use HTTPS for remote repositories; HTTP is local-loopback only");
        }
    }

    public URI uri() {
        return URI.create(value);
    }

    /** Repository identity with trailing path slashes removed while authored text stays intact. */
    public String normalizedIdentity() {
        int query = value.indexOf('?');
        int pathEnd = query >= 0 ? query : value.length();
        int normalizedEnd = pathEnd;
        while (normalizedEnd > 0 && value.charAt(normalizedEnd - 1) == '/') {
            normalizedEnd--;
        }
        return value.substring(0, normalizedEnd) + value.substring(pathEnd);
    }

    private static URI parse(String value) {
        if (value.isBlank() || !value.equals(value.trim())) {
            throw invalid(value, "use a non-empty URL without surrounding whitespace");
        }
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || uri.isOpaque()) {
                throw invalid(value, "use an absolute hierarchical HTTP(S) URL");
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid repository URL `" + value + "`.", exception);
        }
    }

    private static boolean isLoopbackHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.equals("localhost")
                || normalized.equals("::1")
                || normalized.equals("0:0:0:0:0:0:0:1")
                || isIpv4Loopback(normalized);
    }

    private static boolean isIpv4Loopback(String host) {
        String[] segments = host.split("\\.", -1);
        if (segments.length != 4 || !segments[0].equals("127")) {
            return false;
        }
        for (String segment : segments) {
            if (segment.isEmpty() || !segment.chars().allMatch(character -> character >= '0' && character <= '9')) {
                return false;
            }
            try {
                if (Integer.parseInt(segment) > 255) {
                    return false;
                }
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        return true;
    }

    private static IllegalArgumentException invalid(String value, String guidance) {
        return new IllegalArgumentException("Invalid repository URL `" + value + "`: " + guidance + ".");
    }
}
