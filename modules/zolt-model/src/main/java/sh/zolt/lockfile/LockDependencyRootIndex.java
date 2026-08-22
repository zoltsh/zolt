package sh.zolt.lockfile;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;

/** Validates the uniqueness and exact selected-package targets of authored dependency roots. */
final class LockDependencyRootIndex {
    private static final EnumSet<DependencyLane> ORDINARY_LANES = EnumSet.of(
            DependencyLane.API,
            DependencyLane.IMPLEMENTATION,
            DependencyLane.RUNTIME,
            DependencyLane.PROVIDED,
            DependencyLane.DEV,
            DependencyLane.TEST);
    private final Map<Key, LockDependencyRoot> roots = new LinkedHashMap<>();
    private final Map<OrdinaryKey, DependencyLane> ordinaryLanes = new LinkedHashMap<>();

    LockDependencyRootIndex(
            List<LockDependencyRoot> dependencyRoots,
            List<LockPackage> packages) {
        for (LockDependencyRoot root : dependencyRoots) {
            Key key = Key.of(root);
            LockDependencyRoot previous = roots.putIfAbsent(key, root);
            if (previous != null) {
                throw invalid("contains duplicate dependency roots for `" + key.description() + "`");
            }
            requireUniqueOrdinaryLane(root);
            if (!root.publishOnly() && packages.stream().noneMatch(root::selects)) {
                throw invalid("dependency root `" + key.description()
                        + "` selects missing package `" + selectedPackage(root) + "`");
            }
        }
    }

    private void requireUniqueOrdinaryLane(LockDependencyRoot root) {
        if (!ORDINARY_LANES.contains(root.lane())) {
            return;
        }
        OrdinaryKey key = new OrdinaryKey(root.member(), root.packageId(), root.variant());
        DependencyLane previous = ordinaryLanes.putIfAbsent(key, root.lane());
        if (previous != null) {
            throw invalid("contains ordinary dependency variant `" + key.description()
                    + "` in both " + previous + " and " + root.lane() + " lanes");
        }
    }

    private static String selectedPackage(LockDependencyRoot root) {
        return root.packageId() + ":" + root.version() + ":" + root.variant().key() + ":"
                + root.resolvedScope().orElseThrow().lockfileName();
    }

    private static LockDependencyGraphException invalid(String detail) {
        return new LockDependencyGraphException(
                "zolt.lock " + detail + ". Run `zolt resolve` to regenerate the lock.");
    }

    private record Key(
            String member,
            DependencyLane lane,
            PackageId packageId,
            LockArtifactVariant variant) {
        static Key of(LockDependencyRoot root) {
            return new Key(root.member(), root.lane(), root.packageId(), root.variant());
        }

        String description() {
            return member + ":" + lane.name().toLowerCase().replace('_', '-') + ":" + packageId + ":" + variant.key();
        }
    }

    private record OrdinaryKey(
            String member,
            PackageId packageId,
            LockArtifactVariant variant) {
        String description() {
            return member + ":" + packageId + ":" + variant.key();
        }
    }
}
