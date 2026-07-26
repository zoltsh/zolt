package sh.zolt.lockfile;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class LockMemberGraphIndex {
    private final Map<Key, LockMemberGraph> graphs = new LinkedHashMap<>();

    public LockMemberGraphIndex(List<LockMemberGraph> memberGraphs) {
        for (LockMemberGraph graph : memberGraphs) {
            Key key = Key.of(graph);
            LockMemberGraph previous = graphs.putIfAbsent(key, graph);
            if (previous != null && !previous.equals(graph)) {
                throw new LockDependencyGraphException(
                        "Workspace zolt.lock contains conflicting member graph facts for `"
                                + graph.member()
                                + "` and `"
                                + graph.packageId()
                                + ":"
                                + graph.version()
                                + ":"
                                + graph.variant().key()
                                + ":"
                                + graph.scope().lockfileName()
                                + "`. Run `zolt resolve --workspace` to regenerate the lock.");
            }
        }
    }

    public LockMemberGraphIndex(
            List<LockMemberGraph> memberGraphs,
            List<LockPackage> packages) {
        this(memberGraphs);
        validateCompleteness(packages);
    }

    public Optional<LockMemberGraph> find(String member, LockPackage lockPackage) {
        return Optional.ofNullable(graphs.get(Key.of(member, lockPackage)));
    }

    public List<String> dependenciesFor(String member, LockPackage lockPackage) {
        return find(member, lockPackage)
                .map(LockMemberGraph::dependencies)
                .orElseGet(lockPackage::dependencies);
    }

    public List<String> policiesFor(String member, LockPackage lockPackage) {
        return find(member, lockPackage)
                .map(LockMemberGraph::policies)
                .orElseGet(lockPackage::policies);
    }

    public boolean declaredOptionalFor(String member, LockPackage lockPackage) {
        return find(member, lockPackage)
                .map(LockMemberGraph::declaredOptional)
                .orElse(false);
    }

    public boolean optionalOnlyFor(String member, LockPackage lockPackage) {
        return find(member, lockPackage)
                .map(LockMemberGraph::optionalOnly)
                .orElse(false);
    }

    private void validateCompleteness(List<LockPackage> packages) {
        for (LockPackage lockPackage : packages) {
            SetKey identity = SetKey.of(lockPackage);
            boolean qualified = graphs.keySet().stream()
                    .anyMatch(key -> SetKey.of(key).equals(identity));
            if (!qualified) {
                continue;
            }
            LinkedHashSet<String> missing =
                    new LinkedHashSet<>(lockPackage.members());
            graphs.keySet().stream()
                    .filter(key -> SetKey.of(key).equals(identity))
                    .map(Key::member)
                    .forEach(missing::remove);
            if (!missing.isEmpty()) {
                throw new LockDependencyGraphException(
                        "Workspace zolt.lock is missing member graph facts for "
                                + missing
                                + " at `"
                                + lockPackage.packageId()
                                + ":"
                                + lockPackage.version()
                                + ":"
                                + LockArtifactVariant.of(lockPackage).key()
                                + ":"
                                + lockPackage.scope().lockfileName()
                                + "`. Run `zolt resolve --workspace` to regenerate the lock.");
            }
        }
    }

    private record Key(
            String member,
            PackageId packageId,
            String version,
            LockArtifactVariant variant,
            DependencyScope scope) {
        static Key of(LockMemberGraph graph) {
            return new Key(
                    graph.member(),
                    graph.packageId(),
                    graph.version(),
                    graph.variant(),
                    graph.scope());
        }

        static Key of(String member, LockPackage lockPackage) {
            return new Key(
                    member,
                    lockPackage.packageId(),
                    lockPackage.version(),
                    LockArtifactVariant.of(lockPackage),
                    lockPackage.scope());
        }
    }

    private record SetKey(
            PackageId packageId,
            String version,
            LockArtifactVariant variant,
            DependencyScope scope) {
        static SetKey of(LockPackage lockPackage) {
            return new SetKey(
                    lockPackage.packageId(),
                    lockPackage.version(),
                    LockArtifactVariant.of(lockPackage),
                    lockPackage.scope());
        }

        static SetKey of(Key key) {
            return new SetKey(
                    key.packageId(),
                    key.version(),
                    key.variant(),
                    key.scope());
        }
    }
}
