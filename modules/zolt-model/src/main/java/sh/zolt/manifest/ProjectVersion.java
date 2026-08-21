package sh.zolt.manifest;

import java.util.Objects;
import sh.zolt.project.VersionPolicy;

/** A fixed literal project package version. */
public record ProjectVersion(String value) {
    public ProjectVersion {
        Objects.requireNonNull(value, "Project version must not be null.");
        VersionPolicy.violation(VersionPolicy.Context.PROJECT_VERSION, value)
                .ifPresent(violation -> {
                    throw new IllegalArgumentException(
                            "Invalid project version `" + value + "`: " + violation.actionableGuidance());
                });
    }

    @Override
    public String toString() {
        return value;
    }
}
