package sh.zolt.manifest.authored;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import sh.zolt.project.toolchain.ToolchainPolicy;

/** Authored fields in the optional {@code [toolchain.java.test]} runtime request. */
public record AuthoredJavaTestToolchain(
        Optional<JavaFeatureRelease> version,
        Optional<JavaDistribution> distribution,
        Optional<ToolchainPolicy> policy) {
    public AuthoredJavaTestToolchain {
        version = Objects.requireNonNull(version, "Authored test Java version must not be null.");
        distribution = Objects.requireNonNull(
                distribution, "Authored test Java distribution must not be null.");
        policy = Objects.requireNonNull(policy, "Authored test Java policy must not be null.");
        if (version.isEmpty() && distribution.isEmpty() && policy.isEmpty()) {
            throw new IllegalArgumentException(
                    "An authored [toolchain.java.test] table must contain at least one field.");
        }
    }
}
