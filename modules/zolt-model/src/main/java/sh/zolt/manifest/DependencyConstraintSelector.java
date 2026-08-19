package sh.zolt.manifest;

import java.util.Objects;
import sh.zolt.project.VersionPolicy;

/** The one exact-version source for an authored strict dependency constraint. */
public sealed interface DependencyConstraintSelector
        permits DependencyConstraintSelector.FixedVersion,
                DependencyConstraintSelector.VersionReference {
    /** A fixed constraint version, whether authored as shorthand or an inline {@code version}. */
    record FixedVersion(String value) implements DependencyConstraintSelector {
        public FixedVersion {
            Objects.requireNonNull(value, "Dependency constraint version must not be null.");
            VersionPolicy.violation(VersionPolicy.Context.CONSTRAINT, value, true)
                    .ifPresent(violation -> {
                        throw new IllegalArgumentException(
                                "Invalid dependency constraint version `" + value + "`: "
                                        + violation.guidance());
                    });
        }
    }

    /** A reference to one exact key in the effective {@code [versions]} map. */
    record VersionReference(LocalId alias) implements DependencyConstraintSelector {
        public VersionReference {
            Objects.requireNonNull(alias, "Dependency constraint version reference must not be null.");
        }
    }
}
