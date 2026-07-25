package sh.zolt.lockfile;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import java.util.LinkedHashMap;
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
}
