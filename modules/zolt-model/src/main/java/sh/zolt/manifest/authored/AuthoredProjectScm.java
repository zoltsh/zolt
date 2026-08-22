package sh.zolt.manifest.authored;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.ManifestModelValues;

/** Authored project source-control metadata, retaining external values exactly. */
public record AuthoredProjectScm(
        Optional<String> url,
        Optional<String> connection,
        Optional<String> developerConnection,
        Optional<String> tag) {
    public AuthoredProjectScm {
        url = nonBlank(url, "Project SCM URL");
        connection = nonBlank(connection, "Project SCM connection");
        developerConnection = nonBlank(
                developerConnection, "Project SCM developer connection");
        tag = nonBlank(tag, "Project SCM tag");
        if (url.isEmpty()
                && connection.isEmpty()
                && developerConnection.isEmpty()
                && tag.isEmpty()) {
            throw new IllegalArgumentException(
                    "Authored project SCM metadata must contain at least one field.");
        }
    }

    private static Optional<String> nonBlank(Optional<String> value, String label) {
        Objects.requireNonNull(value, label + " must not be null.");
        value.ifPresent(item -> ManifestModelValues.requireNonBlank(item, label));
        return value;
    }
}
