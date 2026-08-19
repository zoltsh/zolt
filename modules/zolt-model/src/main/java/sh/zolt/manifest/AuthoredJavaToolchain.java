package sh.zolt.manifest;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import sh.zolt.project.toolchain.ToolchainPolicy;

/** Authored fields in the optional main {@code [toolchain.java]} request. */
public record AuthoredJavaToolchain(
        Optional<JavaFeatureRelease> version,
        Optional<JavaDistribution> distribution,
        Optional<Set<JavaFeature>> features,
        Optional<ToolchainPolicy> policy) {
    public AuthoredJavaToolchain {
        version = Objects.requireNonNull(version, "Authored Java toolchain version must not be null.");
        distribution = Objects.requireNonNull(
                distribution, "Authored Java toolchain distribution must not be null.");
        features = Objects.requireNonNull(features, "Authored Java toolchain features must not be null.")
                .map(AuthoredJavaToolchain::immutableFeatures);
        policy = Objects.requireNonNull(policy, "Authored Java toolchain policy must not be null.");
        if (version.isEmpty()
                && distribution.isEmpty()
                && features.map(Set::isEmpty).orElse(true)
                && policy.isEmpty()) {
            throw new IllegalArgumentException(
                    "An authored [toolchain.java] table must contain at least one meaningful field.");
        }
    }

    private static Set<JavaFeature> immutableFeatures(Set<JavaFeature> values) {
        Objects.requireNonNull(values, "Authored Java toolchain feature set must not be null.");
        LinkedHashSet<JavaFeature> ordered = values.stream()
                .map(feature -> Objects.requireNonNull(feature, "Java toolchain feature must not be null."))
                .sorted(Comparator.comparing(JavaFeature::id))
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        return Collections.unmodifiableSet(ordered);
    }
}
