package sh.zolt.manifest;

import java.util.Objects;
import java.util.Optional;

/** Canonical dependency artifact identity with an explicit default type and classifier absence. */
public record DependencyVariant(
        DependencyCoordinate coordinate,
        String type,
        Optional<String> classifier) implements Comparable<DependencyVariant> {
    public static final String DEFAULT_TYPE = "jar";

    public DependencyVariant {
        Objects.requireNonNull(coordinate, "Dependency variant coordinate must not be null.");
        type = DependencyVariantValue.type(type);
        Objects.requireNonNull(classifier, "Dependency variant classifier must not be null.");
        classifier = classifier.map(DependencyVariantValue::classifier);
    }

    public static DependencyVariant of(AuthoredDependency dependency) {
        Objects.requireNonNull(dependency, "Authored dependency must not be null.");
        return new DependencyVariant(
                dependency.coordinate(),
                dependency.metadata().type().orElse(DEFAULT_TYPE),
                dependency.metadata().classifier());
    }

    public boolean isDefaultArtifact() {
        return DEFAULT_TYPE.equals(type) && classifier.isEmpty();
    }

    /** The lock-compatible normalized artifact discriminator. */
    public String artifactKey() {
        return classifier.map(value -> type + "|" + value).orElse(type);
    }

    /** A stable key for the full coordinate and normalized artifact discriminator. */
    public String key() {
        return coordinate + "|" + artifactKey();
    }

    @Override
    public int compareTo(DependencyVariant other) {
        int byCoordinate = coordinate.compareTo(other.coordinate);
        if (byCoordinate != 0) {
            return byCoordinate;
        }
        int byType = type.compareTo(other.type);
        if (byType != 0) {
            return byType;
        }
        return classifier.orElse("").compareTo(other.classifier.orElse(""));
    }
}
