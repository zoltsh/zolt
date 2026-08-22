package sh.zolt.manifest;

import java.util.Objects;
import java.util.Optional;

/** An explicitly authored {@code repositories.central} value. */
public sealed interface CentralRepositoryControl
        permits CentralRepositoryControl.Enabled,
                CentralRepositoryControl.Disabled,
                CentralRepositoryControl.Replacement {
    /** Explicit {@code central = true}; omission remains represented separately. */
    record Enabled() implements CentralRepositoryControl {
    }

    /** Explicit {@code central = false}. */
    record Disabled() implements CentralRepositoryControl {
    }

    /** An unauthenticated string replacement or credential-capable inline replacement. */
    record Replacement(RepositoryUrl url, Optional<LocalId> credentials) implements CentralRepositoryControl {
        public Replacement {
            Objects.requireNonNull(url, "Central replacement URL must not be null.");
            credentials = Objects.requireNonNull(
                    credentials, "Central replacement credential reference must not be null.");
        }
    }
}
