package sh.zolt.tree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockDependencyEdge;
import sh.zolt.lockfile.LockDependencyGraphException;
import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;

/** Authoritative lock-v7 roots, kept separate from resolved package occurrence facts. */
final class DependencyRootProjection {
    private static final Comparator<Root> ROOT_ORDER = Comparator
            .comparing(Root::member)
            .thenComparingInt(root -> root.lane().canonicalOrder())
            .thenComparing(root -> root.packageId().toString())
            .thenComparing(root -> root.source().variant().key());

    private final LockDependencyIndex packages;
    private final List<Root> roots;
    private final String regenerateCommand;

    private DependencyRootProjection(
            ZoltLockfile lockfile,
            Set<String> members,
            String regenerateCommand) {
        this.regenerateCommand = regenerateCommand;
        requireVersionSeven(lockfile, regenerateCommand);
        packages = new LockDependencyIndex(lockfile.packages());
        ArrayList<Root> projected = new ArrayList<>();
        for (LockDependencyRoot root : lockfile.dependencyRoots()) {
            if (!members.contains(root.member())) {
                throw invalidMember(root.member(), members, regenerateCommand);
            }
            Optional<LockPackage> selected = root.publishOnly()
                    ? Optional.empty()
                    : packages.resolveGraphEdge(edge(root), regenerateCommand);
            projected.add(new Root(root, selected));
        }
        projected.sort(ROOT_ORDER);
        roots = List.copyOf(projected);
    }

    static DependencyRootProjection standalone(ZoltLockfile lockfile) {
        return new DependencyRootProjection(lockfile, Set.of("."), "zolt resolve");
    }

    static DependencyRootProjection workspace(
            ZoltLockfile lockfile,
            List<String> members) {
        return new DependencyRootProjection(
                lockfile,
                Set.copyOf(members),
                "zolt resolve --workspace");
    }

    List<Root> roots() {
        return roots;
    }

    List<Root> rootsFor(String member) {
        return roots.stream().filter(root -> root.member().equals(member)).toList();
    }

    /** Coordinates of materialized graph roots; publish-only declarations have no package node. */
    List<String> graphRootCoordinates() {
        TreeSet<String> coordinates = new TreeSet<>();
        roots.stream()
                .filter(root -> root.selected().isPresent())
                .forEach(root -> coordinates.add(root.coordinate()));
        return List.copyOf(coordinates);
    }

    boolean selects(LockPackage lockPackage) {
        return roots.stream().anyMatch(root -> root.selected().filter(lockPackage::equals).isPresent());
    }

    Optional<ResolvedPath> pathTo(PackageId target) {
        ArrayDeque<PathItem> queue = new ArrayDeque<>();
        rootsFor(".").stream()
                .filter(root -> root.selected().isPresent())
                .forEach(root -> {
                    LockPackage selected = root.selected().orElseThrow();
                    queue.add(new PathItem(root, selected, List.of(selected)));
                });
        while (!queue.isEmpty()) {
            PathItem item = queue.removeFirst();
            if (item.lockPackage().packageId().equals(target)) {
                return Optional.of(new ResolvedPath(item.root(), item.path()));
            }
            item.lockPackage().dependencies().stream()
                    .sorted()
                    .map(edge -> packages.resolveGraphEdge(edge, regenerateCommand).orElseThrow())
                    .filter(dependency -> !contains(item.path(), dependency))
                    .forEach(dependency -> queue.add(new PathItem(
                            item.root(),
                            dependency,
                            append(item.path(), dependency))));
        }
        return Optional.empty();
    }

    Optional<Root> publishOnlyRoot(PackageId target) {
        return rootsFor(".").stream()
                .filter(Root::publishOnly)
                .filter(root -> root.packageId().equals(target))
                .findFirst();
    }

    private static void requireVersionSeven(
            ZoltLockfile lockfile,
            String regenerateCommand) {
        if (lockfile.version() != ZoltLockfile.CURRENT_VERSION) {
            throw new LockDependencyGraphException(
                    "zolt.lock version " + lockfile.version()
                            + " cannot prove authored dependency lanes. Run `"
                            + regenerateCommand
                            + "` to regenerate lockfile version "
                            + ZoltLockfile.CURRENT_VERSION + ".");
        }
    }

    private static LockDependencyGraphException invalidMember(
            String member,
            Set<String> members,
            String regenerateCommand) {
        return new LockDependencyGraphException(
                "zolt.lock dependency root member `" + member
                        + "` is not in the projected member set "
                        + new TreeSet<>(members) + ". Run `"
                        + regenerateCommand + "` to regenerate zolt.lock.");
    }

    private static String edge(LockDependencyRoot root) {
        return LockDependencyEdge.encode(
                root.packageId(),
                root.version(),
                root.variant(),
                root.resolvedScope().orElseThrow());
    }

    private static boolean contains(
            List<LockPackage> path,
            LockPackage candidate) {
        String identity = LockDependencyEdge.of(candidate).encode();
        return path.stream().anyMatch(lockPackage ->
                LockDependencyEdge.of(lockPackage).encode().equals(identity));
    }

    private static List<LockPackage> append(
            List<LockPackage> path,
            LockPackage lockPackage) {
        ArrayList<LockPackage> updated = new ArrayList<>(path);
        updated.add(lockPackage);
        return List.copyOf(updated);
    }

    record Root(LockDependencyRoot source, Optional<LockPackage> selected) {
        Root {
            source = Objects.requireNonNull(source, "Dependency root source must not be null.");
            selected = Objects.requireNonNull(selected, "Dependency root selection must not be null.");
        }

        String member() {
            return source.member();
        }

        PackageId packageId() {
            return source.packageId();
        }

        DependencyLane lane() {
            return source.lane();
        }

        String laneName() {
            return lane().name().toLowerCase().replace('_', '-');
        }

        boolean publishOnly() {
            return source.publishOnly();
        }

        String coordinate() {
            return source.packageId() + ":" + source.version()
                    + (source.variant().isDefault() ? "" : ":" + source.variant().key());
        }

        String annotation() {
            StringBuilder value = new StringBuilder("lane: ").append(laneName());
            source.resolvedScope().ifPresent(scope ->
                    value.append("; resolved scope: ").append(scope.lockfileName()));
            if (source.optional()) {
                value.append("; optional");
            }
            if (source.publishOnly()) {
                value.append("; publish only");
            }
            return value.toString();
        }
    }

    record ResolvedPath(Root root, List<LockPackage> packages) {
        ResolvedPath {
            packages = List.copyOf(packages);
        }
    }

    private record PathItem(
            Root root,
            LockPackage lockPackage,
            List<LockPackage> path) {
    }
}
