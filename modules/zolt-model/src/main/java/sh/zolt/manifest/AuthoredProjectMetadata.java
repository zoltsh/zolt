package sh.zolt.manifest;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Authored project-local main class and publication metadata. */
public record AuthoredProjectMetadata(
        Optional<JavaBinaryClassName> main,
        Optional<String> description,
        Optional<String> url,
        Optional<String> issues,
        Optional<AuthoredProjectScm> scm,
        Map<LocalId, AuthoredProjectDeveloper> developers) {
    public AuthoredProjectMetadata {
        main = Objects.requireNonNull(main, "Project main class must not be null.");
        description = nonBlank(description, "Project description");
        url = nonBlank(url, "Project URL");
        issues = nonBlank(issues, "Project issues URL");
        scm = Objects.requireNonNull(scm, "Project SCM metadata must not be null.");
        developers = ManifestModelValues.immutableSortedMap(
                developers,
                Comparator.naturalOrder(),
                "Project developer ID",
                "Project developer metadata");
    }

    public static AuthoredProjectMetadata empty() {
        return new AuthoredProjectMetadata(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of());
    }

    private static Optional<String> nonBlank(Optional<String> value, String label) {
        Objects.requireNonNull(value, label + " must not be null.");
        value.ifPresent(item -> ManifestModelValues.requireNonBlank(item, label));
        return value;
    }
}
