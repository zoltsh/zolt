package sh.zolt.manifest.authored;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.LocalId;

/** The authored identity, membership, and narrow project defaults of one workspace root. */
public record AuthoredWorkspace(
        LocalId name,
        AuthoredWorkspaceMembers members,
        Optional<AuthoredWorkspaceProjectDefaults> projectDefaults) {
    public AuthoredWorkspace {
        Objects.requireNonNull(name, "Workspace name must not be null.");
        Objects.requireNonNull(members, "Workspace members must not be null.");
        Objects.requireNonNull(projectDefaults, "Workspace project defaults must not be null.");
    }
}
