package sh.zolt.project.toolchain;

import java.util.Objects;
import sh.zolt.project.VersionPolicy;

/** Fixed released Groovy compiler version requested by a manifest toolchain table. */
public record GroovyToolchainVersion(String value) {
    public GroovyToolchainVersion {
        Objects.requireNonNull(value, "Groovy toolchain version is required.");
        VersionPolicy.violation(VersionPolicy.Context.TOOL_DEPENDENCY, value)
                .ifPresent(violation -> {
                    throw new IllegalArgumentException(
                            "Invalid Groovy toolchain version `" + value + "`: "
                                    + violation.actionableGuidance());
                });
    }

    @Override
    public String toString() {
        return value;
    }
}
