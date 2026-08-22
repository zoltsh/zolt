package sh.zolt.manifest.authored;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.ManifestModelValues;

/** One nonempty authored developer record under {@code [project.developers.<id>]}. */
public record AuthoredProjectDeveloper(
        Optional<String> name,
        Optional<String> email,
        Optional<String> organization,
        Optional<String> url) {
    public AuthoredProjectDeveloper {
        name = nonBlank(name, "Project developer name");
        email = nonBlank(email, "Project developer email");
        organization = nonBlank(organization, "Project developer organization");
        url = nonBlank(url, "Project developer URL");
        if (name.isEmpty() && email.isEmpty() && organization.isEmpty() && url.isEmpty()) {
            throw new IllegalArgumentException(
                    "Authored project developer metadata must contain at least one field.");
        }
    }

    private static Optional<String> nonBlank(Optional<String> value, String label) {
        Objects.requireNonNull(value, label + " must not be null.");
        value.ifPresent(item -> ManifestModelValues.requireNonBlank(item, label));
        return value;
    }
}
