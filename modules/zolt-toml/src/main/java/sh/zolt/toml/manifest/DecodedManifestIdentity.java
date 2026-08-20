package sh.zolt.toml.manifest;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.authored.AuthoredProject;
import sh.zolt.manifest.authored.AuthoredWorkspace;

/** Authored workspace and project domains decoded without applying workspace context. */
record DecodedManifestIdentity(
        Optional<AuthoredWorkspace> workspace,
        Optional<AuthoredProject> project) {
    DecodedManifestIdentity {
        workspace = Objects.requireNonNull(workspace, "Decoded workspace must not be null.");
        project = Objects.requireNonNull(project, "Decoded project must not be null.");
    }
}
