package sh.zolt.manifest.authored;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.DependencyCoordinate;

/** Optional authored dependency fields whose legality also depends on selector and lane. */
public record AuthoredDependencyMetadata(
        boolean optional,
        boolean publishOnly,
        Optional<String> classifier,
        Optional<String> type,
        List<DependencyCoordinate> exclusions) {
    private static final AuthoredDependencyMetadata NONE =
            new AuthoredDependencyMetadata(false, false, Optional.empty(), Optional.empty(), List.of());

    public AuthoredDependencyMetadata {
        Objects.requireNonNull(classifier, "Dependency classifier must not be null.");
        Objects.requireNonNull(type, "Dependency type must not be null.");
        Objects.requireNonNull(exclusions, "Dependency exclusions must not be null.");
        classifier = classifier.map(DependencyVariantValue::classifier);
        type = type.map(DependencyVariantValue::type);
        for (DependencyCoordinate exclusion : exclusions) {
            Objects.requireNonNull(exclusion, "Dependency exclusion must not be null.");
        }
        exclusions = List.copyOf(exclusions);
    }

    public static AuthoredDependencyMetadata none() {
        return NONE;
    }

    public boolean hasExternalArtifactMetadata() {
        return classifier.isPresent() || type.isPresent() || !exclusions.isEmpty();
    }
}
