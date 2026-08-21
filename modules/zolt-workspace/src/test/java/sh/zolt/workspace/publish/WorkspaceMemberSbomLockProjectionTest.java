package sh.zolt.workspace.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.resolve.WorkspaceMemberPolicyResolver;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class WorkspaceMemberSbomLockProjectionTest {
    private static final String SHA_DATABIND =
            "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String SHA_CORE =
            "2222222222222222222222222222222222222222222222222222222222222222";
    private static final String SHA_GUAVA =
            "3333333333333333333333333333333333333333333333333333333333333333";
    private static final String SHA_LANG =
            "5555555555555555555555555555555555555555555555555555555555555555";

    private final WorkspaceMemberSbomLockProjection projection = new WorkspaceMemberSbomLockProjection();
    private final WorkspaceMemberPolicyResolver resolver = new WorkspaceMemberPolicyResolver();

    @Test
    void carriesTheFullClosureHashesAndEdgesWithMemberViewDirectness() {
        // acme-http declares jackson-databind directly, a workspace sibling acme-core, and a test-only dep.
        ProjectConfig memberConfig = config(
                "acme-http",
                Map.of("com.fasterxml.jackson.core:jackson-databind", "2.19.2"),
                Map.of("com.acme:acme-core", "acme-core"),
                Map.of("org.junit.jupiter:junit-jupiter", "5.11.4"));
        // The sibling declares nothing of its own — it contributes only itself and the root -> sibling edge.
        Workspace workspace = workspaceOf(
                member("acme-http", memberConfig),
                member("acme-core", config("acme-core", Map.of(), Map.of(), Map.of())));

        // The aggregated lock: sibling + a direct external whose edge reaches a transitive external, plus a
        // guava that is direct for SOME OTHER member (OR'd direct) and a test-scope dep — neither reachable.
        ZoltLockfile aggregated = aggregate(
                List.of(
                        workspacePackage("com.acme", "acme-core", "1.0.0", "acme-http"),
                        external("com.fasterxml.jackson.core", "jackson-databind", "2.19.2",
                                DependencyScope.COMPILE, false, SHA_DATABIND,
                                List.of("com.fasterxml.jackson.core:jackson-core:2.19.2")),
                        // jackson-core is direct=true in the aggregated lock (another member declares it),
                        // but acme-http only reaches it transitively.
                        external("com.fasterxml.jackson.core", "jackson-core", "2.19.2",
                                DependencyScope.COMPILE, true, SHA_CORE, List.of()),
                        externalWithMembers("com.google.guava", "guava", "33.0.0-jre",
                                DependencyScope.COMPILE, true, SHA_GUAVA, List.of(), List.of("other-member")),
                        external("org.junit.jupiter", "junit-jupiter", "5.11.4",
                                DependencyScope.TEST, true, SHA_GUAVA, List.of())),
                List.of(
                        root("acme-http", "com.acme", "acme-core", "1.0.0",
                                DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE),
                        root("acme-http", "com.fasterxml.jackson.core", "jackson-databind", "2.19.2",
                                DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE),
                        root("acme-http", "org.junit.jupiter", "junit-jupiter", "5.11.4",
                                DependencyLane.TEST, DependencyScope.TEST)));

        ZoltLockfile projected =
                projection.project("acme-http", memberConfig, aggregated, workspace, resolver);

        Map<String, LockPackage> byCoordinate = index(projected);
        // Closure retains every authored lane; downstream SBOM scope selection filters test.
        assertEquals(4, projected.packages().size());
        assertTrue(byCoordinate.containsKey("com.fasterxml.jackson.core:jackson-databind"));
        assertTrue(byCoordinate.containsKey("com.fasterxml.jackson.core:jackson-core"));
        assertTrue(byCoordinate.containsKey("com.acme:acme-core"));
        // The OR'd-direct guava belongs to another member; the authored test root remains represented.
        assertFalse(byCoordinate.containsKey("com.google.guava:guava"));
        assertTrue(byCoordinate.containsKey("org.junit.jupiter:junit-jupiter"));

        // Hashes are carried through AS-IS (the POM projection would have dropped them).
        LockPackage databind = byCoordinate.get("com.fasterxml.jackson.core:jackson-databind");
        LockPackage core = byCoordinate.get("com.fasterxml.jackson.core:jackson-core");
        assertEquals(Optional.of(SHA_DATABIND), databind.jarSha256());
        assertEquals(Optional.of(SHA_CORE), core.jarSha256());
        // The dependency edge is preserved so the assembler can render direct -> transitive.
        assertEquals(List.of("com.fasterxml.jackson.core:jackson-core:2.19.2"), databind.dependencies());

        // Directness is the member's view, NOT the aggregated OR'd flag: the declared direct and the
        // sibling are direct; the transitive is NOT (even though it is direct=true in the aggregated lock).
        assertTrue(databind.direct());
        assertTrue(byCoordinate.get("com.acme:acme-core").direct());
        assertFalse(core.direct());

        // The sibling is preserved as a first-party workspace component (source + marker intact).
        LockPackage sibling = byCoordinate.get("com.acme:acme-core");
        assertEquals("workspace", sibling.source());
        assertTrue(sibling.workspace().isPresent());
    }

    @Test
    void walksTheDeeperTransitiveChain() {
        // A -> B -> C: the member declares only A; B and C must both be pulled in as transitives.
        ProjectConfig memberConfig = config("acme-http", Map.of("com.example:a", "1.0.0"), Map.of(), Map.of());
        Workspace workspace = workspaceOf(member("acme-http", memberConfig));
        ZoltLockfile aggregated = aggregate(
                List.of(
                        external("com.example", "a", "1.0.0", DependencyScope.COMPILE, true, SHA_DATABIND,
                                List.of("com.example:b:1.0.0")),
                        external("com.example", "b", "1.0.0", DependencyScope.COMPILE, false, SHA_CORE,
                                List.of("com.example:c:1.0.0")),
                        external("com.example", "c", "1.0.0", DependencyScope.RUNTIME, false, SHA_GUAVA,
                                List.of())),
                List.of(root("acme-http", "com.example", "a", "1.0.0",
                        DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE)));

        Map<String, LockPackage> byCoordinate =
                index(projection.project("acme-http", memberConfig, aggregated, workspace, resolver));

        assertEquals(3, byCoordinate.size());
        assertTrue(byCoordinate.get("com.example:a").direct());
        assertFalse(byCoordinate.get("com.example:b").direct());
        assertFalse(byCoordinate.get("com.example:c").direct());
        assertEquals(Optional.of(SHA_GUAVA), byCoordinate.get("com.example:c").jarSha256());
    }

    @Test
    void carriesASiblingOwnedExternalAndItsEdgeIntoTheMemberClosure() {
        // The review's exact scenario: acme-http -> acme-core (workspace) -> guava (acme-core's external).
        // acme-core's lock entry carries no edges, so the projection must synthesize acme-core -> guava.
        ProjectConfig memberConfig =
                config("acme-http", Map.of(), Map.of("com.acme:acme-core", "acme-core"), Map.of());
        Workspace workspace = workspaceOf(
                member("acme-http", memberConfig),
                member("acme-core", config(
                        "acme-core", Map.of("com.google.guava:guava", "33.0.0-jre"), Map.of(), Map.of())));

        ZoltLockfile aggregated = aggregate(
                List.of(
                        workspacePackage("com.acme", "acme-core", "1.0.0", "acme-http"),
                        // guava is attributed to acme-core, direct=true (OR'd), and carries no edges of its own.
                        externalWithMembers("com.google.guava", "guava", "33.0.0-jre",
                                DependencyScope.COMPILE, true, SHA_GUAVA, List.of(), List.of("acme-core"))),
                List.of(
                        root("acme-http", "com.acme", "acme-core", "1.0.0",
                                DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE),
                        root("acme-core", "com.google.guava", "guava", "33.0.0-jre",
                                DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE)));

        ZoltLockfile projected =
                projection.project("acme-http", memberConfig, aggregated, workspace, resolver);
        Map<String, LockPackage> byCoordinate = index(projected);

        // Both the sibling AND its external are present (guava is no longer dropped at the sibling).
        assertEquals(2, projected.packages().size());
        LockPackage core = byCoordinate.get("com.acme:acme-core");
        LockPackage guava = byCoordinate.get("com.google.guava:guava");
        assertTrue(core != null, "sibling acme-core present");
        assertTrue(guava != null, "sibling-owned external guava present");
        // guava's SHA-256 is carried through as-is (supply-chain evidence, never reconstructed).
        assertEquals(Optional.of(SHA_GUAVA), guava.jarSha256());
        // The synthetic edge acme-core -> guava lives on the sibling's projected dependencies list.
        assertEquals(List.of("com.google.guava:guava:33.0.0-jre:jar:compile"), core.dependencies());
        // Only the member's own declaration (acme-core) is root-direct; the sibling-owned external is NOT.
        assertTrue(core.direct());
        assertFalse(guava.direct());
        // The sibling keeps its first-party workspace identity even with edges synthesized onto it.
        assertEquals("workspace", core.source());
        assertTrue(core.workspace().isPresent());
    }

    @Test
    void resolvesATransitiveSiblingChainAndNeverMakesSiblingExternalsRootDirect() {
        // http -> core(workspace) -> util(workspace) -> commons-lang(external): a siblings-of-siblings chain.
        ProjectConfig memberConfig =
                config("acme-http", Map.of(), Map.of("com.acme:acme-core", "acme-core"), Map.of());
        Workspace workspace = workspaceOf(
                member("acme-http", memberConfig),
                member("acme-core", config(
                        "acme-core", Map.of(), Map.of("com.acme:acme-util", "acme-util"), Map.of())),
                member("acme-util", config(
                        "acme-util", Map.of("org.apache.commons:commons-lang3", "3.14.0"), Map.of(), Map.of())));

        ZoltLockfile aggregated = aggregate(
                List.of(
                        workspacePackage("com.acme", "acme-core", "1.0.0", "acme-http"),
                        workspacePackage("com.acme", "acme-util", "1.0.0", "acme-core"),
                        externalWithMembers("org.apache.commons", "commons-lang3", "3.14.0",
                                DependencyScope.COMPILE, false, SHA_LANG, List.of(), List.of("acme-util"))),
                List.of(
                        root("acme-http", "com.acme", "acme-core", "1.0.0",
                                DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE),
                        root("acme-core", "com.acme", "acme-util", "1.0.0",
                                DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE),
                        root("acme-util", "org.apache.commons", "commons-lang3", "3.14.0",
                                DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE)));

        ZoltLockfile projected =
                projection.project("acme-http", memberConfig, aggregated, workspace, resolver);
        Map<String, LockPackage> byCoordinate = index(projected);

        // All three graph nodes below the root resolve: both siblings and the deep external.
        assertEquals(3, projected.packages().size());
        LockPackage core = byCoordinate.get("com.acme:acme-core");
        LockPackage util = byCoordinate.get("com.acme:acme-util");
        LockPackage lang = byCoordinate.get("org.apache.commons:commons-lang3");
        assertTrue(core != null && util != null && lang != null, "core, util, and commons-lang3 all present");
        // Edges: core -> util (workspace sibling) and util -> commons-lang (deep sibling external).
        assertEquals(List.of("com.acme:acme-util:1.0.0:jar:compile"), core.dependencies());
        assertEquals(List.of("org.apache.commons:commons-lang3:3.14.0:jar:compile"), util.dependencies());
        assertEquals(Optional.of(SHA_LANG), lang.jarSha256());
        // Only the member's own direct (acme-core) is root-direct; the transitive sibling and its external
        // are NOT — sibling-owned externals never become root-direct.
        assertTrue(core.direct());
        assertFalse(util.direct());
        assertFalse(lang.direct());
    }

    @Test
    void prefersTheVariantAttributedToTheSiblingWhenResolvingItsDirectExternal() {
        // Forward-compatibility with workspace variant identity: two lock entries share the guava GA, and
        // only one is attributed (via members) to acme-core. The synthetic edge must point at THAT entry.
        ProjectConfig memberConfig =
                config("acme-http", Map.of(), Map.of("com.acme:acme-core", "acme-core"), Map.of());
        Workspace workspace = workspaceOf(
                member("acme-http", memberConfig),
                member("acme-core", config(
                        "acme-core", Map.of("com.google.guava:guava", "33.0.0-jre"), Map.of(), Map.of())));

        ZoltLockfile aggregated = aggregate(
                List.of(
                        workspacePackage("com.acme", "acme-core", "1.0.0", "acme-http"),
                        // A guava variant owned by a DIFFERENT member — must not be the one acme-core links.
                        externalWithMembers("com.google.guava", "guava", "33.0.0-android",
                                DependencyScope.COMPILE, false, SHA_CORE, List.of(), List.of("other-member")),
                        // The guava variant actually attributed to acme-core.
                        externalWithMembers("com.google.guava", "guava", "33.0.0-jre",
                                DependencyScope.COMPILE, false, SHA_GUAVA, List.of(), List.of("acme-core"))),
                List.of(
                        root("acme-http", "com.acme", "acme-core", "1.0.0",
                                DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE),
                        root("acme-core", "com.google.guava", "guava", "33.0.0-jre",
                                DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE)));

        Map<String, LockPackage> byCoordinate =
                index(projection.project("acme-http", memberConfig, aggregated, workspace, resolver));
        LockPackage core = byCoordinate.get("com.acme:acme-core");
        // The synthesized edge resolves to the acme-core-attributed variant (jre@SHA_GUAVA), not android.
        assertEquals(List.of("com.google.guava:guava:33.0.0-jre:jar:compile"), core.dependencies());
        assertEquals(Optional.of(SHA_GUAVA), byCoordinate.get("com.google.guava:guava").jarSha256());
    }

    private static ProjectConfig config(
            String name,
            Map<String, String> dependencies,
            Map<String, String> workspaceDependencies,
            Map<String, String> testDependencies) {
        return ProjectConfigs.withWorkspaceDependencySections(
                new ProjectMetadata(name, "1.0.0", "com.acme", "21", Optional.empty()),
                Map.of("central", ProjectConfig.MAVEN_CENTRAL),
                Map.of(),
                dependencies,
                Set.of(),
                workspaceDependencies,
                testDependencies,
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                BuildSettings.defaults(),
                NativeSettings.defaults());
    }

    private static WorkspaceMember member(String path, ProjectConfig config) {
        return new WorkspaceMember(path, Path.of("/ws").resolve(path), config);
    }

    private static Workspace workspaceOf(WorkspaceMember... members) {
        List<String> paths = new ArrayList<>();
        for (WorkspaceMember member : members) {
            paths.add(member.path());
        }
        return new Workspace(
                Path.of("/ws"),
                Path.of("/ws/zolt.toml"),
                new WorkspaceConfig("acme", paths, List.of(), Map.of(), Map.of()),
                List.of(members));
    }

    private static Map<String, LockPackage> index(ZoltLockfile lockfile) {
        LinkedHashMap<String, LockPackage> byCoordinate = new LinkedHashMap<>();
        for (LockPackage lockPackage : lockfile.packages()) {
            byCoordinate.put(
                    lockPackage.packageId().groupId() + ":" + lockPackage.packageId().artifactId(), lockPackage);
        }
        return byCoordinate;
    }

    private static LockPackage external(
            String group,
            String artifact,
            String version,
            DependencyScope scope,
            boolean direct,
            String jarSha256,
            List<String> dependencies) {
        String base = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version;
        return new LockPackage(
                new PackageId(group, artifact),
                version,
                "maven-central",
                scope,
                direct,
                Optional.of(base + ".jar"),
                Optional.of(base + ".pom"),
                Optional.of(jarSha256),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                dependencies,
                List.of("acme-http"));
    }

    private static LockPackage externalWithMembers(
            String group,
            String artifact,
            String version,
            DependencyScope scope,
            boolean direct,
            String jarSha256,
            List<String> dependencies,
            List<String> members) {
        String base = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version;
        return new LockPackage(
                new PackageId(group, artifact),
                version,
                "maven-central",
                scope,
                direct,
                Optional.of(base + ".jar"),
                Optional.of(base + ".pom"),
                Optional.of(jarSha256),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                dependencies,
                members);
    }

    private static ZoltLockfile aggregate(
            List<LockPackage> packages,
            List<LockDependencyRoot> roots) {
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                packages,
                List.of(),
                List.of(),
                List.of(),
                roots);
    }

    private static LockDependencyRoot root(
            String member,
            String group,
            String artifact,
            String version,
            DependencyLane lane,
            DependencyScope scope) {
        return root(
                member,
                group,
                artifact,
                version,
                LockArtifactVariant.defaultVariant(),
                lane,
                scope,
                false);
    }

    private static LockDependencyRoot root(
            String member,
            String group,
            String artifact,
            String version,
            LockArtifactVariant variant,
            DependencyLane lane,
            DependencyScope scope,
            boolean optional) {
        return new LockDependencyRoot(
                member,
                new PackageId(group, artifact),
                version,
                variant,
                lane,
                Optional.of(scope),
                optional,
                false);
    }

    private static LockPackage workspacePackage(
            String group,
            String artifact,
            String version,
            String... members) {
        return new LockPackage(
                new PackageId(group, artifact),
                version,
                "workspace",
                DependencyScope.COMPILE,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(artifact),
                Optional.of("target/classes"),
                List.of(),
                List.of(members));
    }
}
