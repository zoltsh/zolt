package sh.zolt.manifest;

import java.util.Objects;
import sh.zolt.project.VersionPolicy;

/** The one source that supplies an authored dependency version or workspace target. */
public sealed interface DependencySelector
        permits DependencySelector.FixedVersion,
                DependencySelector.VersionReference,
                DependencySelector.Managed,
                DependencySelector.Workspace {
    /** A fixed external version, whether authored as shorthand or an inline {@code version}. */
    record FixedVersion(String value) implements DependencySelector {
        public FixedVersion {
            Objects.requireNonNull(value, "Dependency version must not be null.");
            VersionPolicy.violation(VersionPolicy.Context.EXTERNAL_DEPENDENCY, value, true)
                    .ifPresent(violation -> {
                        throw new IllegalArgumentException(
                                "Invalid dependency version `" + value + "`: " + violation.guidance());
                    });
        }
    }

    /** A reference to one exact key in the authored {@code [versions]} map. */
    record VersionReference(LocalId alias) implements DependencySelector {
        public VersionReference {
            Objects.requireNonNull(alias, "Dependency version reference must not be null.");
        }
    }

    /** A version selected by an imported platform. */
    record Managed() implements DependencySelector {
    }

    /** A dependency resolved by effective workspace project identity. */
    record Workspace() implements DependencySelector {
    }
}
