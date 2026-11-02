package sh.zolt.project.toolchain;

import java.util.Objects;
import sh.zolt.project.VersionPolicy;

/** Fixed released Kotlin compiler version requested by a manifest toolchain table. */
public record KotlinToolchainVersion(String value) {
    public KotlinToolchainVersion {
        Objects.requireNonNull(value, "Kotlin toolchain version is required.");
        VersionPolicy.violation(VersionPolicy.Context.TOOL_DEPENDENCY, value)
                .ifPresent(violation -> {
                    throw new IllegalArgumentException(
                            "Invalid Kotlin toolchain version `" + value + "`: "
                                    + violation.actionableGuidance());
                });
    }

    @Override
    public String toString() {
        return value;
    }
}
