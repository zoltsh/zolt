package sh.zolt.manifest;

import java.util.Objects;
import java.util.Optional;

/** Authored artifact-signing configuration from {@code [publish.signing]}. */
public record AuthoredPublicationSigning(
        Method method,
        Optional<String> keyId,
        Optional<EnvironmentVariableName> passphraseEnvironment) {
    public AuthoredPublicationSigning {
        Objects.requireNonNull(method, "Publication signing method must not be null.");
        keyId = Objects.requireNonNull(keyId, "Publication signing key ID must not be null.");
        keyId.ifPresent(value -> {
            ManifestModelValues.requireNonBlank(value, "Publication signing key ID");
            ManifestModelValues.rejectControlCharacters(value, "Publication signing key ID");
        });
        passphraseEnvironment = Objects.requireNonNull(
                passphraseEnvironment,
                "Publication signing passphrase environment-variable name must not be null.");
    }

    /** Closed signing-method vocabulary for the final 0.1.0 language. */
    public enum Method {
        GPG("gpg");

        private final String configValue;

        Method(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }
    }
}
