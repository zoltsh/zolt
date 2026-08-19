package sh.zolt.manifest.effective;

import java.util.Objects;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import sh.zolt.project.toolchain.ToolchainPolicy;

/** Effective test Java runtime, either identical to main or a separate feature-free request. */
public sealed interface EffectiveTestJavaRuntime
        permits EffectiveTestJavaRuntime.SameAsMain, EffectiveTestJavaRuntime.Requested {
    /** Tests use the exact effective main Java runtime. */
    record SameAsMain(EffectiveJavaRuntime main) implements EffectiveTestJavaRuntime {
        public SameAsMain {
            main = Objects.requireNonNull(main, "Effective main Java runtime must not be null.");
        }
    }

    /** A separate test runtime request; Java features do not inherit into this request. */
    record Requested(
            EffectiveValue<JavaFeatureRelease> version,
            EffectiveValue<JavaDistribution> distribution,
            EffectiveValue<ToolchainPolicy> policy)
            implements EffectiveTestJavaRuntime {
        public Requested {
            version = Objects.requireNonNull(
                    version, "Requested test Java version must not be null.");
            distribution = Objects.requireNonNull(
                    distribution, "Requested test Java distribution must not be null.");
            policy = Objects.requireNonNull(
                    policy, "Requested test Java policy must not be null.");
        }
    }
}
