package sh.zolt.manifest.effective;

import java.util.Objects;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.authored.AuthoredDependency;

/** One managed declaration awaiting exact selection from resolved platform metadata. */
public record EffectiveManagedDependencyRequest(
        WorkspaceMemberPath owner,
        AuthoredDependency declaration) implements Comparable<EffectiveManagedDependencyRequest> {
    public EffectiveManagedDependencyRequest {
        owner = Objects.requireNonNull(owner, "Managed dependency owner must not be null.");
        declaration = Objects.requireNonNull(
                declaration, "Managed dependency declaration must not be null.");
        if (!(declaration.selector() instanceof DependencySelector.Managed)) {
            throw new IllegalArgumentException(
                    "An effective managed dependency request requires `managed = true`.");
        }
    }

    @Override
    public int compareTo(EffectiveManagedDependencyRequest other) {
        int byOwner = owner.compareTo(other.owner);
        if (byOwner != 0) {
            return byOwner;
        }
        int byLane = Integer.compare(
                declaration.lane().canonicalOrder(),
                other.declaration.lane().canonicalOrder());
        if (byLane != 0) {
            return byLane;
        }
        return declaration.variant().compareTo(other.declaration.variant());
    }
}
