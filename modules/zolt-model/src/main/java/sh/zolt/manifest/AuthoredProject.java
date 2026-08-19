package sh.zolt.manifest;

import java.util.Objects;

/** Complete parser-independent authored {@code [project]} domain. */
public record AuthoredProject(
        AuthoredProjectIdentity identity,
        AuthoredProjectMetadata metadata) {
    public AuthoredProject {
        Objects.requireNonNull(identity, "Authored project identity must not be null.");
        Objects.requireNonNull(metadata, "Authored project metadata must not be null.");
    }
}
