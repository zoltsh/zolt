package sh.zolt.maven.repository;

import static sh.zolt.maven.repository.RepositoryHttpRequests.diagnosticUri;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Resolves one repository-relative path without permitting origin or base-path escape. */
final class RepositoryArtifactUri {
    private RepositoryArtifactUri() {
    }

    static URI resolve(URI repositoryBaseUri, String path) {
        if (repositoryBaseUri == null || path == null || path.isBlank()) {
            throw new RepositoryClientException("Repository base URI and relative path are required.");
        }
        requireRelative(path);
        URI base = normalizedBase(repositoryBaseUri);
        URI resolved;
        try {
            resolved = base.resolve(URI.create(encodePath(path))).normalize();
        } catch (IllegalArgumentException exception) {
            throw new RepositoryClientException("Repository path `" + path + "` is not a safe relative URI.", exception);
        }
        requireContained(base, resolved, path);
        return resolved;
    }

    private static URI normalizedBase(URI repositoryBaseUri) {
        URI base = repositoryBaseUri.normalize();
        if (base.getScheme() == null || base.getRawAuthority() == null
                || base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new RepositoryClientException(
                    "Repository base URI must include an origin and cannot include a query or fragment: "
                            + diagnosticUri(repositoryBaseUri) + ".");
        }
        String path = base.getRawPath();
        if (path != null && !path.isEmpty() && path.endsWith("/")) {
            return base;
        }
        try {
            return URI.create(base.toString() + "/").normalize();
        } catch (IllegalArgumentException exception) {
            throw new RepositoryClientException("Repository base URI cannot be normalized.", exception);
        }
    }

    private static void requireContained(URI base, URI resolved, String path) {
        boolean sameOrigin = base.getScheme().equals(resolved.getScheme())
                && base.getRawAuthority().equals(resolved.getRawAuthority());
        String resolvedPath = resolved.getRawPath();
        if (!sameOrigin
                || resolved.getRawQuery() != null
                || resolved.getRawFragment() != null
                || resolvedPath == null
                || !resolvedPath.startsWith(base.getRawPath())) {
            throw new RepositoryClientException(
                    "Repository path `" + path + "` escapes the configured repository base.");
        }
    }

    private static void requireRelative(String path) {
        if (path.startsWith("/") || path.startsWith("\\")) {
            throw new RepositoryClientException("Repository path `" + path + "` must be relative.");
        }
        for (int index = 0; index < path.length(); index++) {
            char character = path.charAt(index);
            if (character == '\\'
                    || character == '?'
                    || character == '#'
                    || character == '%'
                    || character == ':'
                    || Character.isISOControl(character)) {
                throw new RepositoryClientException("Repository path `" + path + "` is not repository-safe.");
            }
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new RepositoryClientException("Repository path `" + path + "` is not repository-safe.");
            }
        }
    }

    private static String encodePath(String path) {
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        char[] hex = "0123456789ABCDEF".toCharArray();
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            char character = (char) unsigned;
            if (unsigned < 128 && (isUnreserved(character) || isPathDelimiter(character))) {
                encoded.append(character);
            } else {
                encoded.append('%')
                        .append(hex[unsigned >>> 4])
                        .append(hex[unsigned & 0x0f]);
            }
        }
        return encoded.toString();
    }

    private static boolean isUnreserved(char character) {
        return character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9'
                || character == '-'
                || character == '.'
                || character == '_'
                || character == '~';
    }

    private static boolean isPathDelimiter(char character) {
        return character == '/'
                || character == '!'
                || character == '$'
                || character == '&'
                || character == '\''
                || character == '('
                || character == ')'
                || character == '*'
                || character == '+'
                || character == ','
                || character == ';'
                || character == '='
                || character == '@';
    }
}
