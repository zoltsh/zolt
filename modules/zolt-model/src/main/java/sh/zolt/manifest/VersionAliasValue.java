package sh.zolt.manifest;

import java.util.Objects;
import sh.zolt.project.VersionPolicy;

/** A fixed literal value owned by one entry in {@code [versions]}. */
public record VersionAliasValue(String value) {
    public VersionAliasValue {
        Objects.requireNonNull(value, "Version alias value must not be null.");
        VersionPolicy.violation(VersionPolicy.Context.VERSION_ALIAS, value, true).ifPresent(violation -> {
            throw new IllegalArgumentException(
                    "Invalid version alias value `" + value + "`: " + violation.guidance());
        });
    }

    @Override
    public String toString() {
        return value;
    }
}
