package sh.zolt.workspace.resolve;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockMemberGraphIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.workspace.state.WorkspaceHash;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The single answer to "which lock packages can appear on this member's lane".
 *
 * <p>Two callers need that answer and must never disagree about it: {@link
 * WorkspaceClasspathLockFactory}, which projects the lane, and {@link WorkspaceMemberStateObserver},
 * which decides from persisted state whether the lane moved. They previously computed it twice —
 * the factory by walking the lock, the observer by folding a coarser bucket set — and the two
 * drifted: the factory places a package attributed to a <em>visible dependency member</em> on the
 * dependent's runtime, test, and package lanes, and carries dependency-attributed transitives onto
 * the compile lane through the exported-API walk, while the observer folded only the member's own
 * bucket. A version bump of such a package moved the lane and not the key.
 *
 * <p>They now share this class, and share it in a way that cannot drift again: one call returns both
 * the lane's package set and the lane's digest, built from the same ordered list of buckets. There is
 * no path that produces one without the other, so a bucket added, removed, or re-scoped moves both or
 * neither.
 *
 * <p>The buckets are the lane rules, factored so each is a property of one member rather than of a
 * member pair:
 *
 * <ul>
 *   <li><strong>shared</strong> — packages naming no member, which every lane sees;
 *   <li><strong>attributed:V</strong> — packages naming {@code V}, which V's own lanes see;
 *   <li><strong>cross:V</strong> — the subset of {@code attributed:V} that contributes across a
 *       workspace boundary (non-optional for V, compile or runtime scope), which every runtime, test,
 *       and package lane that can see V picks up;
 *   <li><strong>exported:V</strong> — everything reachable in the exported-API compile walk seeded at
 *       V, which every compile lane whose closure contains V picks up. The walk never crosses members,
 *       so the closure over a set of members is exactly the union of the per-member closures;
 *   <li><strong>workspace:W</strong> — the lock's own record of member W's output.
 * </ul>
 *
 * <p>Bucket digests fold the member-qualified graph facts ({@code optional}, {@code policies},
 * {@code dependencies}) beside the package identity, because those are what the projected lane view
 * reads — a graph row that changes what the lane resolves to must move the key even when the package
 * record itself is untouched.
 */
public final class WorkspaceMemberLaneClosure {
    private final ZoltLockfile lockfile;
    private final LockDependencyIndex dependencyIndex;
    private final LockMemberGraphIndex memberGraphs;
    private final WorkspaceMemberVisibility visibility;
    private final Bucket shared;
    private final Map<String, Bucket> attributed;
    private final Map<String, Bucket> crossBoundary;
    private final Map<String, Bucket> workspaces;
    private final Map<String, Bucket> exportedCompile = new LinkedHashMap<>();
    private final Map<String, Lane> lanes = new LinkedHashMap<>();

    public WorkspaceMemberLaneClosure(
            ZoltLockfile lockfile,
            LockDependencyIndex dependencyIndex,
            LockMemberGraphIndex memberGraphs,
            WorkspaceMemberVisibility visibility) {
        this.lockfile = lockfile;
        this.dependencyIndex = dependencyIndex;
        this.memberGraphs = memberGraphs;
        this.visibility = visibility;
        BucketBuilder sharedPackages = new BucketBuilder();
        Map<String, BucketBuilder> attributedPackages = new LinkedHashMap<>();
        Map<String, BucketBuilder> crossBoundaryPackages = new LinkedHashMap<>();
        Map<String, BucketBuilder> workspacePackages = new LinkedHashMap<>();
        List<LockPackage> packages = lockfile.packages();
        for (int index = 0; index < packages.size(); index++) {
            LockPackage lockPackage = packages.get(index);
            if (lockPackage.workspace().isPresent()) {
                workspacePackages
                        .computeIfAbsent(lockPackage.workspace().orElseThrow(), ignored -> new BucketBuilder())
                        .add(index, identity(lockPackage));
                continue;
            }
            if (lockPackage.members().isEmpty()) {
                sharedPackages.add(index, identity(lockPackage));
                continue;
            }
            for (String member : lockPackage.members()) {
                String identity = memberIdentity(member, lockPackage);
                attributedPackages
                        .computeIfAbsent(member, ignored -> new BucketBuilder())
                        .add(index, identity);
                if (crossesWorkspaceBoundary(member, lockPackage)) {
                    crossBoundaryPackages
                            .computeIfAbsent(member, ignored -> new BucketBuilder())
                            .add(index, identity);
                }
            }
        }
        this.shared = sharedPackages.build("shared");
        this.attributed = buckets("attributed", attributedPackages);
        this.crossBoundary = buckets("cross", crossBoundaryPackages);
        this.workspaces = buckets("workspace", workspacePackages);
    }

    /** The lane the member compiles its main sources against. */
    public synchronized Lane mainCompile(String memberPath) {
        return lanes.computeIfAbsent(
                "compile|" + memberPath,
                ignored -> compileLane(memberPath, visibility.mainCompile(memberPath)));
    }

    /** The lane the member's runtime and package inputs come from. */
    public synchronized Lane mainRuntime(String memberPath) {
        return lanes.computeIfAbsent(
                "runtime|" + memberPath,
                ignored -> runtimeLane(memberPath, visibility.mainRuntime(memberPath)));
    }

    /** The lane the member compiles and runs its tests against. */
    public synchronized Lane test(String memberPath) {
        return lanes.computeIfAbsent(
                "test|" + memberPath,
                ignored -> runtimeLane(memberPath, visibility.test(memberPath)));
    }

    /**
     * A package on the member's compile lane either names nobody, names the member, or was reached by
     * the exported-API walk from a member in the compile closure. Workspace records ride along for the
     * closure's members.
     */
    private Lane compileLane(String memberPath, Set<String> compileMembers) {
        List<Bucket> buckets = new ArrayList<>();
        buckets.add(shared);
        buckets.add(bucket(attributed, "attributed", memberPath));
        for (String member : new TreeSet<>(compileMembers)) {
            buckets.add(exportedCompile(member));
            buckets.add(bucket(workspaces, "workspace", member));
        }
        return lane(buckets);
    }

    /**
     * A package on the member's runtime, test, or package lane either names nobody, names the member,
     * or crosses the workspace boundary from a member the lane can see — which includes the member
     * itself and every member of the lane's dependency closure.
     */
    private Lane runtimeLane(String memberPath, Set<String> dependencyMembers) {
        List<Bucket> buckets = new ArrayList<>();
        buckets.add(shared);
        buckets.add(bucket(attributed, "attributed", memberPath));
        buckets.add(bucket(crossBoundary, "cross", memberPath));
        for (String member : new TreeSet<>(dependencyMembers)) {
            buckets.add(bucket(crossBoundary, "cross", member));
            buckets.add(bucket(workspaces, "workspace", member));
        }
        return lane(buckets);
    }

    private Bucket exportedCompile(String member) {
        Bucket cached = exportedCompile.get(member);
        if (cached != null) {
            return cached;
        }
        Set<String> reached =
                WorkspaceExportedCompileClosure.compute(
                        lockfile, Set.of(member), dependencyIndex, memberGraphs);
        BucketBuilder builder = new BucketBuilder();
        List<LockPackage> packages = lockfile.packages();
        for (int index = 0; index < packages.size() && !reached.isEmpty(); index++) {
            LockPackage lockPackage = packages.get(index);
            if (lockPackage.workspace().isEmpty()
                    && reached.contains(WorkspaceExportedCompileClosure.ref(lockPackage))) {
                builder.add(index, memberIdentity(member, lockPackage));
            }
        }
        Bucket bucket = builder.build("exported:" + member);
        exportedCompile.put(member, bucket);
        return bucket;
    }

    private boolean crossesWorkspaceBoundary(String member, LockPackage lockPackage) {
        DependencyScope scope = lockPackage.scope();
        return (scope == DependencyScope.COMPILE || scope == DependencyScope.RUNTIME)
                && !memberGraphs.optionalOnlyFor(member, lockPackage);
    }

    private String memberIdentity(String member, LockPackage lockPackage) {
        return identity(lockPackage)
                + "|optionalOnly="
                + memberGraphs.optionalOnlyFor(member, lockPackage)
                + "|declaredOptional="
                + memberGraphs.declaredOptionalFor(member, lockPackage)
                + "|memberPolicies="
                + String.join(",", memberGraphs.policiesFor(member, lockPackage))
                + "|memberDependencies="
                + String.join(",", memberGraphs.dependenciesFor(member, lockPackage));
    }

    private static Lane lane(List<Bucket> buckets) {
        BitSet packages = new BitSet();
        StringBuilder content = new StringBuilder();
        for (Bucket bucket : buckets) {
            packages.or(bucket.packages());
            content.append(bucket.id()).append('=').append(bucket.digest()).append('\n');
        }
        return new Lane(packages, WorkspaceHash.text(content.toString()));
    }

    private static Bucket bucket(Map<String, Bucket> buckets, String kind, String member) {
        Bucket bucket = buckets.get(member);
        return bucket == null ? Bucket.empty(kind + ":" + member) : bucket;
    }

    private static Map<String, Bucket> buckets(String kind, Map<String, BucketBuilder> builders) {
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        builders.forEach((member, builder) -> buckets.put(member, builder.build(kind + ":" + member)));
        return Map.copyOf(buckets);
    }

    private static String identity(LockPackage lockPackage) {
        return String.join(
                "|",
                lockPackage.packageId().toString(),
                lockPackage.version(),
                lockPackage.scope().name(),
                lockPackage.jar().orElse(""),
                lockPackage.jarSha256().orElse(""),
                lockPackage.pom().orElse(""),
                lockPackage.pomSha256().orElse(""),
                lockPackage.artifact().orElse(""),
                lockPackage.artifactType().orElse(""),
                lockPackage.artifactSha256().orElse(""),
                lockPackage.workspace().orElse(""),
                lockPackage.workspaceOutput().orElse(""),
                String.join(",", lockPackage.dependencies()),
                String.join(",", lockPackage.members()),
                String.join(",", lockPackage.exportedBy()),
                String.join(",", lockPackage.policies()));
    }

    /**
     * One member's view of one lane: which of the root lock's packages belong on it, and the digest
     * that moves whenever that membership or any member-qualified fact behind it moves.
     */
    public record Lane(BitSet packages, String digest) {
        /** Whether the package at {@code packageIndex} of the root lock belongs on this lane. */
        public boolean contains(int packageIndex) {
            return packages.get(packageIndex);
        }
    }

    private record Bucket(String id, BitSet packages, String digest) {
        static Bucket empty(String id) {
            return new Bucket(id, new BitSet(), WorkspaceHash.text(""));
        }
    }

    private static final class BucketBuilder {
        private final BitSet packages = new BitSet();
        private final List<String> identities = new ArrayList<>();

        void add(int index, String identity) {
            packages.set(index);
            identities.add(identity);
        }

        Bucket build(String id) {
            return new Bucket(
                    id,
                    packages,
                    WorkspaceHash.text(String.join("\n", new TreeSet<>(identities))));
        }
    }
}
