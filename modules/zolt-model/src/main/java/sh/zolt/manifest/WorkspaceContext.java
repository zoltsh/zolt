package sh.zolt.manifest;

import java.util.Objects;

/** Portable workspace identity and selected member associated with an effective project. */
public record WorkspaceContext(
        EffectiveValue<LocalId> name,
        WorkspaceMemberPath memberPath) {
    public WorkspaceContext {
        name = Objects.requireNonNull(name, "Effective workspace name must not be null.");
        memberPath = Objects.requireNonNull(memberPath, "Workspace member path must not be null.");
        if (name.origin() == ValueOrigin.BUILT_IN) {
            throw new IllegalArgumentException(
                    "Effective workspace name must be authored or inherited.");
        }
    }
}
