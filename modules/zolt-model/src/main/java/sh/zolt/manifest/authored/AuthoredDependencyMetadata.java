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
        // An explicit `jar` names the default variant design §9.7 already assumes, and the canonical
        // writer omits it on rewrite. Retaining it would leave one variant identity with two model
        // spellings — enough to wedge the source-preserving editor on any declaration that carried it,
        // and to publish a POM the canonical manifest does not describe.
        type = type.map(DependencyVariantValue::type)
                .filter(value -> !DependencyVariant.DEFAULT_TYPE.equals(value));
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
