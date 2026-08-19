package sh.zolt.manifest;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import sh.zolt.project.toolchain.ToolchainPolicy;

/** Effective Java runtime selection before a locked vendor build is chosen. */
public sealed interface EffectiveJavaRuntime
        permits EffectiveJavaRuntime.System, EffectiveJavaRuntime.Requested {
    /** The current system JDK, constrained by the project's effective Java release. */
    record System(EffectiveValue<JavaFeatureRelease> requiredRelease)
            implements EffectiveJavaRuntime {
        public System {
            requiredRelease = Objects.requireNonNull(
                    requiredRelease, "System Java required release must not be null.");
        }
    }

    /** A complete managed/system-selection request after request defaults are applied. */
    record Requested(
            EffectiveValue<JavaFeatureRelease> version,
            EffectiveValue<JavaDistribution> distribution,
            EffectiveValue<Set<JavaFeature>> features,
            EffectiveValue<ToolchainPolicy> policy)
            implements EffectiveJavaRuntime {
        public Requested {
            version = Objects.requireNonNull(version, "Requested Java version must not be null.");
            distribution = Objects.requireNonNull(
                    distribution, "Requested Java distribution must not be null.");
            features = Objects.requireNonNull(
                            features, "Requested Java features must not be null.")
                    .map(Requested::immutableFeatures);
            policy = Objects.requireNonNull(policy, "Requested Java policy must not be null.");
        }

        private static Set<JavaFeature> immutableFeatures(Set<JavaFeature> values) {
            Objects.requireNonNull(values, "Requested Java feature set must not be null.");
            LinkedHashSet<JavaFeature> ordered = values.stream()
                    .map(feature -> Objects.requireNonNull(
                            feature, "Requested Java feature must not be null."))
                    .sorted(Comparator.comparing(JavaFeature::id))
                    .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
            return Collections.unmodifiableSet(ordered);
        }
    }
}
