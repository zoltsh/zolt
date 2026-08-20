package sh.zolt.manifest.authored;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.RepositoryUrl;

/** Authored Maven Central Portal configuration from {@code [publish.central]}. */
public record AuthoredCentralPublishing(
        EnvironmentVariableName tokenEnvironment,
        Mode mode,
        Optional<String> name,
        Optional<RepositoryUrl> url) {
    public AuthoredCentralPublishing {
        Objects.requireNonNull(
                tokenEnvironment, "Central token environment-variable name must not be null.");
        Objects.requireNonNull(mode, "Central publication mode must not be null.");
        name = Objects.requireNonNull(name, "Central deployment name must not be null.");
        name.ifPresent(value -> {
            ManifestModelValues.requireNonBlank(value, "Central deployment name");
            ManifestModelValues.rejectControlCharacters(value, "Central deployment name");
        });
        url = Objects.requireNonNull(url, "Central Portal URL must not be null.");
    }

    /** Closed Central publication-mode vocabulary for the final 0.1.0 language. */
    public enum Mode {
        MANUAL("manual"),
        AUTOMATIC("automatic");

        private final String configValue;

        Mode(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }
    }
}
