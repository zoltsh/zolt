package sh.zolt.manifest;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.license.SpdxCatalog;

/** The authored project-license shorthand or inline metadata form. */
public sealed interface ProjectLicense permits ProjectLicense.Identifier, ProjectLicense.Metadata {
    /** The string shorthand, which accepts exactly one current SPDX License List identifier. */
    record Identifier(String id) implements ProjectLicense {
        public Identifier {
            Objects.requireNonNull(id, "Project license identifier must not be null.");
            id = SpdxCatalog.defaultCatalog()
                    .canonicalLicense(id)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Project license shorthand requires one current SPDX license identifier."));
        }
    }

    /** The inline metadata form used for custom or compound licenses. */
    record Metadata(Optional<String> id, Optional<String> name, Optional<String> url)
            implements ProjectLicense {
        public Metadata {
            id = requiredOptional(id, "Project license metadata id");
            name = requiredOptional(name, "Project license metadata name");
            url = requiredOptional(url, "Project license metadata URL");
            if (id.isEmpty() && name.isEmpty()) {
                throw new IllegalArgumentException(
                        "Project license metadata requires at least an id or name.");
            }
        }

        private static Optional<String> requiredOptional(Optional<String> value, String subject) {
            Objects.requireNonNull(value, subject + " must not be null.");
            value.ifPresent(item -> {
                if (item.isBlank()) {
                    throw new IllegalArgumentException(subject + " must not be blank.");
                }
            });
            return value;
        }
    }
}
