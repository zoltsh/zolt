package sh.zolt.manifest;

import java.util.Objects;
import sh.zolt.project.VersionPolicy;

/** The exact authored source of an imported platform version. */
public sealed interface PlatformSelector
        permits PlatformSelector.FixedVersion, PlatformSelector.VersionReference {
    /** A fixed platform version, whether authored as a string or {@code version} inline field. */
    record FixedVersion(String value) implements PlatformSelector {
        public FixedVersion {
            Objects.requireNonNull(value, "Platform version must not be null.");
            VersionPolicy.violation(VersionPolicy.Context.PLATFORM, value, true).ifPresent(violation -> {
                throw new IllegalArgumentException(
                        "Invalid platform version `" + value + "`: " + violation.guidance());
            });
        }
    }

    /** A reference to one exact key in the effective {@code [versions]} map. */
    record VersionReference(LocalId alias) implements PlatformSelector {
        public VersionReference {
            Objects.requireNonNull(alias, "Platform version reference must not be null.");
        }
    }
}
