package sh.zolt.manifest;

import java.util.Objects;

/** One exact environment-backed HTTP authentication form. */
public sealed interface RepositoryCredential
        permits RepositoryCredential.BearerToken, RepositoryCredential.Basic {
    /** HTTP bearer authentication. */
    record BearerToken(EnvironmentVariableName tokenEnvironment) implements RepositoryCredential {
        public BearerToken {
            Objects.requireNonNull(tokenEnvironment, "Bearer token environment-variable name must not be null.");
        }
    }

    /** HTTP Basic authentication. */
    record Basic(EnvironmentVariableName usernameEnvironment, EnvironmentVariableName passwordEnvironment)
            implements RepositoryCredential {
        public Basic {
            Objects.requireNonNull(usernameEnvironment, "Basic username environment-variable name must not be null.");
            Objects.requireNonNull(passwordEnvironment, "Basic password environment-variable name must not be null.");
        }
    }
}
