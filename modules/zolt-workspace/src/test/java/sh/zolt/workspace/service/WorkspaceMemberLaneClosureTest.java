package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.workspace.WorkspaceContentAddressedLockTestSupport.dependencyRoot;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockDependencyEdge;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.workspace.resolve.WorkspaceMemberLaneClosure;
import sh.zolt.workspace.WorkspaceConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The invariant the lane closure exists to hold: no lock edit that moves any lane of a member can
 * leave that member's key for the lane unchanged.
 *
 * <p>The lock below is shaped like a real workspace lock rather than a minimal one, because the
 * misses this pins came from the shapes a minimal lock does not have: a package attributed to a
 * <em>dependency</em> member that still lands on the dependent's runtime and test lanes, and an
 * exported-API transitive two hops from the exporting member that lands on the dependent's compile
 * lane. Both moved a dependent's lane while leaving the old per-member digest untouched.
 */
final class WorkspaceMemberLaneClosureTest {
    private static final List<String> MEMBERS =
            List.of("apps/api", "modules/core", "modules/util");

    private final ZoltLockfileReader lockfileReader = new ZoltLockfileReader();

    @TempDir
    private Path tempDir;

    /**
     * The reviewer's test-lane repro: {@code org.example:core-runtime} is attributed to
     * {@code modules/core} only, yet the factory puts it on {@code apps/api}'s runtime, test, and
     * package lanes because core is visible there. A version bump must move api's keys.
     */
    @Test
    void aDependencyAttributedPackageBumpMovesTheDependentsRuntimeTestAndPackageKeys()
            throws IOException {
        WorkspaceMemberLaneClosure before = closure(lock("1.0.0", "1.0.0", "1.0.0"));
        WorkspaceMemberLaneClosure after = closure(lock("2.0.0", "1.0.0", "1.0.0"));

        assertNotEquals(
                before.mainRuntime("apps/api").digest(),
                after.mainRuntime("apps/api").digest(),
                "the runtime lane moved");
        assertNotEquals(
                before.test("apps/api").digest(),
                after.test("apps/api").digest(),
                "the test lane moved");
        assertNotEquals(
                before.mainRuntime("modules/core").digest(),
                after.mainRuntime("modules/core").digest(),
                "the attributed member's own lane moved");
    }

    /**
     * {@code org.example:api-deep} is attributed to {@code modules/core}, is not exported, and is two
     * hops past the package core does export — so neither it nor the edge that names it appears in
     * anything api itself declares. It still lands on api's compile lane through the exported-API
     * walk, and a bump of it must move api's compile key.
     */
    @Test
    void anExportedCompileTransitiveBumpMovesTheDependentsCompileKey() throws IOException {
        WorkspaceMemberLaneClosure before = closure(lock("1.0.0", "1.0.0", "1.0.0"));
        WorkspaceMemberLaneClosure after = closure(lock("1.0.0", "1.0.0", "2.0.0"));

        assertTrue(
                laneCoordinates(lock("1.0.0", "1.0.0", "1.0.0"), before.mainCompile("apps/api"))
                        .contains("org.example:api-deep"),
                "the depth-two transitive really is on the dependent's compile lane");
        assertNotEquals(
                before.mainCompile("apps/api").digest(),
                after.mainCompile("apps/api").digest());
    }

    /** A lane the edit cannot reach must stay put, or widening the digest would readmit everyone. */
    @Test
    void aBumpOutsideALaneLeavesThatLaneAlone() throws IOException {
        WorkspaceMemberLaneClosure before = closure(lock("1.0.0", "1.0.0", "1.0.0"));
        WorkspaceMemberLaneClosure after = closure(lock("1.0.0", "2.0.0", "1.0.0"));

        assertEquals(
                before.mainCompile("modules/core").digest(),
                after.mainCompile("modules/core").digest(),
                "a package attributed to apps/api alone cannot reach a leaf member's lane");
        assertEquals(
                before.mainRuntime("modules/core").digest(),
                after.mainRuntime("modules/core").digest());
    }

    /**
     * The audit: bump every package in the lock in turn and require the conservative superset — every
     * member and lane whose <em>projected</em> packages changed must also have a changed digest. This
     * is what catches a future lane rule that only one of the two callers learns about.
     */
    @Test
    void everyLaneThatMovesUnderAnyPackageBumpAlsoMovesItsKey() throws IOException {
        ZoltLockfile baseline = lock("1.0.0", "1.0.0", "1.0.0");
        Map<String, List<String>> baselineLanes = projectedLanes(baseline);
        Map<String, String> baselineDigests = laneDigests(baseline);
        List<String> unmovedKeys = new ArrayList<>();
        int movedLanes = 0;

        for (int index = 0; index < baseline.packages().size(); index++) {
            ZoltLockfile bumped = bumpPackage(baseline, index);
            Map<String, List<String>> lanes = projectedLanes(bumped);
            Map<String, String> digests = laneDigests(bumped);
            for (String lane : baselineLanes.keySet()) {
                if (baselineLanes.get(lane).equals(lanes.get(lane))) {
                    continue;
                }
                movedLanes++;
                if (baselineDigests.get(lane).equals(digests.get(lane))) {
                    unmovedKeys.add(
                            lane + " moved when " + coordinate(baseline, index) + " was bumped");
                }
            }
        }

        assertEquals(List.of(), unmovedKeys);
        assertTrue(movedLanes > 0, "the audit must actually move lanes to be worth anything");
    }

    private Map<String, List<String>> projectedLanes(ZoltLockfile lockfile) throws IOException {
        WorkspaceExecutionContext context = context(lockfile);
        WorkspaceClasspathLockFactory factory = new WorkspaceClasspathLockFactory();
        Map<String, List<String>> lanes = new LinkedHashMap<>();
        for (String member : MEMBERS) {
            lanes.put(member + "/compile", coordinates(factory.compileLock(context, member)));
            lanes.put(member + "/runtime", coordinates(factory.runtimeLock(context, member)));
            lanes.put(member + "/test", coordinates(factory.testLock(context, member)));
            lanes.put(member + "/package", coordinates(factory.packageLock(context, member)));
        }
        return lanes;
    }

    private Map<String, String> laneDigests(ZoltLockfile lockfile) throws IOException {
        WorkspaceMemberLaneClosure closure = closure(lockfile);
        Map<String, String> digests = new LinkedHashMap<>();
        for (String member : MEMBERS) {
            digests.put(member + "/compile", closure.mainCompile(member).digest());
            digests.put(member + "/runtime", closure.mainRuntime(member).digest());
            digests.put(member + "/test", closure.test(member).digest());
            digests.put(member + "/package", closure.mainRuntime(member).digest());
        }
        return digests;
    }

    private static List<String> coordinates(ZoltLockfile lockfile) {
        return lockfile.packages().stream().map(WorkspaceMemberLaneClosureTest::coordinate).toList();
    }

    private List<String> laneCoordinates(
            ZoltLockfile lockfile,
            WorkspaceMemberLaneClosure.Lane lane) {
        List<LockPackage> packages = lockfile.packages();
        List<String> coordinates = new ArrayList<>();
        for (int index = 0; index < packages.size(); index++) {
            if (lane.contains(index)) {
                coordinates.add(packages.get(index).packageId().toString());
            }
        }
        return coordinates;
    }

    private static String coordinate(LockPackage lockPackage) {
        return lockPackage.packageId()
                + ":"
                + lockPackage.version()
                + ":"
                + lockPackage.scope().lockfileName()
                + ":"
                + lockPackage.members();
    }

    private static String coordinate(ZoltLockfile lockfile, int index) {
        return coordinate(lockfile.packages().get(index));
    }

    /**
     * Rewrites one package's version and re-points every edge that named it, which is exactly what a
     * dependency upgrade does to a lock.
     */
    private static ZoltLockfile bumpPackage(ZoltLockfile lockfile, int index) {
        LockPackage original = lockfile.packages().get(index);
        LockPackage bumped =
                rewritten(original, original.version() + "-bumped", original.dependencies());
        String oldEdge = LockDependencyEdge.of(original).encode();
        String newEdge = LockDependencyEdge.of(bumped).encode();
        List<LockPackage> packages = new ArrayList<>();
        for (int position = 0; position < lockfile.packages().size(); position++) {
            LockPackage lockPackage =
                    position == index ? bumped : lockfile.packages().get(position);
            packages.add(rewritten(
                    lockPackage,
                    lockPackage.version(),
                    lockPackage.dependencies().stream()
                            .map(edge -> edge.equals(oldEdge) ? newEdge : edge)
                            .toList()));
        }
        return new ZoltLockfile(lockfile.version(), packages, List.of());
    }

    private static LockPackage rewritten(
            LockPackage original,
            String version,
            List<String> dependencies) {
        return new LockPackage(
                original.packageId(),
                version,
                original.source(),
                original.scope(),
                original.direct(),
                original.jar(),
                original.pom(),
                original.jarSha256(),
                original.pomSha256(),
                original.artifact(),
                original.artifactType(),
                original.artifactSha256(),
                original.workspace(),
                original.workspaceOutput(),
                dependencies,
                original.members(),
                original.exportedBy(),
                original.policies(),
                original.toolGroups());
    }

    private WorkspaceMemberLaneClosure closure(ZoltLockfile lockfile) throws IOException {
        return context(lockfile).laneClosure();
    }

    private WorkspaceExecutionContext context(ZoltLockfile lockfile) throws IOException {
        return new WorkspaceExecutionContext(workspace(), lockfile, tempDir.resolve("cache"));
    }

    private ZoltLockfile lock(
            String coreRuntimeVersion,
            String apiOnlyVersion,
            String apiDeepVersion) {
        String packages = """
                version = 7

                [[package]]
                id = "com.acme:core"
                version = "0.1.0"
                source = "workspace"
                scope = "compile"
                direct = true
                workspace = "modules/core"
                workspaceOutput = "target/classes"
                members = ["modules/util"]
                dependencies = []

                [[package]]
                id = "com.acme:util"
                version = "0.1.0"
                source = "workspace"
                scope = "compile"
                direct = true
                workspace = "modules/util"
                workspaceOutput = "target/classes"
                members = ["apps/api"]
                dependencies = []

                [[package]]
                id = "org.example:everyones"
                version = "3.0.0"
                source = "maven-central"
                scope = "compile"
                direct = false
                jar = "org/example/everyones/3.0.0/everyones-3.0.0.jar"
                dependencies = []

                [[package]]
                id = "org.example:core-runtime"
                version = "%s"
                source = "maven-central"
                scope = "runtime"
                direct = true
                jar = "org/example/core-runtime/core-runtime.jar"
                members = ["modules/core"]
                dependencies = []

                [[package]]
                id = "org.example:api-only"
                version = "%s"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "org/example/api-only/api-only.jar"
                members = ["apps/api"]
                dependencies = []

                [[package]]
                id = "org.example:core-api"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "org/example/core-api/core-api.jar"
                members = ["modules/core"]
                exportedBy = ["modules/core"]
                dependencies = ["org.example:api-transitive:1.0.0:jar:compile"]

                [[package]]
                id = "org.example:api-transitive"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = false
                jar = "org/example/api-transitive/api-transitive.jar"
                members = ["modules/core"]
                dependencies = ["org.example:api-deep:%s:jar:compile"]

                [[package]]
                id = "org.example:api-deep"
                version = "%s"
                source = "maven-central"
                scope = "compile"
                direct = false
                jar = "org/example/api-deep/api-deep.jar"
                members = ["modules/core"]
                dependencies = []

                [[package]]
                id = "org.example:core-test"
                version = "1.0.0"
                source = "maven-central"
                scope = "test"
                direct = true
                jar = "org/example/core-test/core-test.jar"
                members = ["modules/core"]
                dependencies = []
                """.formatted(
                        coreRuntimeVersion,
                        apiOnlyVersion,
                        apiDeepVersion,
                        apiDeepVersion);
        return lockfileReader.read(packages
                + dependencyRoot("modules/util", "com.acme:core", "0.1.0", DependencyLane.API, DependencyScope.COMPILE)
                + dependencyRoot("apps/api", "com.acme:util", "0.1.0", DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE)
                + dependencyRoot("modules/core", "org.example:core-runtime", coreRuntimeVersion, DependencyLane.RUNTIME, DependencyScope.RUNTIME)
                + dependencyRoot("apps/api", "org.example:api-only", apiOnlyVersion, DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE)
                + dependencyRoot("modules/core", "org.example:core-api", "1.0.0", DependencyLane.API, DependencyScope.COMPILE)
                + dependencyRoot("modules/core", "org.example:core-test", "1.0.0", DependencyLane.TEST, DependencyScope.TEST));
    }

    /**
     * {@code apps/api} depends on {@code modules/util}, which re-exports {@code modules/core}: the
     * exported edge is what carries core's API packages onto api's compile lane.
     */
    private Workspace workspace() throws IOException {
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), "");
        for (String member : MEMBERS) {
            Files.createDirectories(tempDir.resolve(member));
        }
        return new Workspace(
                tempDir,
                tempDir.resolve("zolt-workspace.toml"),
                new WorkspaceConfig("acme-platform", MEMBERS, List.of(), Map.of(), Map.of()),
                MEMBERS.stream()
                        .map(member -> new WorkspaceMember(member, tempDir.resolve(member), null))
                        .toList(),
                List.of(
                        new WorkspaceProjectEdge(
                                "apps/api", "modules/util", "compile", "com.acme:util"),
                        new WorkspaceProjectEdge(
                                "modules/util", "modules/core", "compile", "com.acme:core", true)),
                MEMBERS);
    }
}
