package sh.zolt.manifest.effective;

import java.util.Objects;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.authored.AuthoredDependency;

/** One authored workspace dependency resolved to its unique effective provider member. */
public record EffectiveWorkspaceDependencyEdge(
        WorkspaceMemberPath consumer,
        WorkspaceMemberPath provider,
        AuthoredDependency declaration) implements Comparable<EffectiveWorkspaceDependencyEdge> {
    public EffectiveWorkspaceDependencyEdge {
        consumer = Objects.requireNonNull(consumer, "Workspace dependency consumer must not be null.");
        provider = Objects.requireNonNull(provider, "Workspace dependency provider must not be null.");
        declaration = Objects.requireNonNull(
                declaration, "Workspace dependency declaration must not be null.");
        if (!(declaration.selector() instanceof DependencySelector.Workspace)) {
            throw new IllegalArgumentException(
                    "An effective workspace dependency edge requires `workspace = true`.");
        }
        if (consumer.equals(provider)) {
            throw new IllegalArgumentException(
                    "An effective workspace dependency edge cannot target its consumer.");
        }
    }

    @Override
    public int compareTo(EffectiveWorkspaceDependencyEdge other) {
        int byConsumer = consumer.compareTo(other.consumer);
        if (byConsumer != 0) {
            return byConsumer;
        }
        int byLane = Integer.compare(
                declaration.lane().canonicalOrder(),
                other.declaration.lane().canonicalOrder());
        if (byLane != 0) {
            return byLane;
        }
        int byVariant = declaration.variant().compareTo(other.declaration.variant());
        return byVariant != 0 ? byVariant : provider.compareTo(other.provider);
    }
}
