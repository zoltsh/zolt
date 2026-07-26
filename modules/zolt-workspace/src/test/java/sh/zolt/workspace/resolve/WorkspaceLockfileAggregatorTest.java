package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileWriter;
import sh.zolt.resolve.ResolveException;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceProjectEdge;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class WorkspaceLockfileAggregatorTest extends WorkspaceLockfileAggregatorTestSupport {
    @Test
    void preservesSingleProjectLockfileForTransitionalRootWorkspace() {
        ZoltLockfile memberLockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.of("sha256:project"),
                List.of("repositories=sha256:repo"),
                List.of(new LockPackage(
                        new PackageId("com.example", "app"),
                        "1.0.0",
                        "central",
                        DependencyScope.COMPILE,
                        true,
                        Optional.of("com/example/app/1.0.0/app-1.0.0.jar"),
                        Optional.of("com/example/app/1.0.0/app-1.0.0.pom"),
                        Optional.of("jar-sha"),
                        Optional.of("pom-sha"),
                        List.of())),
                List.of(),
                List.of());
        Workspace workspace = new Workspace(
                Path.of("/repo"),
                Path.of("/repo/zolt-workspace.toml"),
                new WorkspaceConfig("zolt", List.of("."), List.of("."), Map.of(), Map.of()),
                List.of(new WorkspaceMember(".", Path.of("/repo"), null)));

        ZoltLockfile aggregated = new WorkspaceLockfileAggregator().aggregate(
                workspace,
                List.of(new WorkspaceMemberResolveOutput(".", memberLockfile, Set.of())));

        assertSame(memberLockfile, aggregated);
    }

    @Test
    void keepsToolAttributedConflictsDistinctAcrossMembers() throws IOException {
        Workspace workspace = workspace(List.of(
                new WorkspaceProjectEdge("apps/api", "modules/core", "compile", "com.acme:core", true)));
        PackageId shared = new PackageId("com.example", "shared");
        LockConflict alphaConflict = new LockConflict(
                shared, "2.0.0", List.of("1.0.0", "2.0.0"),
                ConflictSelectionReason.DIRECT_DEPENDENCY, Optional.of("alpha"));
        LockConflict betaConflict = new LockConflict(
                shared, "2.0.0", List.of("1.0.0", "2.0.0"),
                ConflictSelectionReason.DIRECT_DEPENDENCY, Optional.of("beta"));

        ZoltLockfile aggregated = new WorkspaceLockfileAggregator().aggregate(
                workspace,
                List.of(
                        new WorkspaceMemberResolveOutput(
                                "apps/api",
                                lockfile(List.of(), List.of(alphaConflict), List.of()),
                                Set.of()),
                        new WorkspaceMemberResolveOutput(
                                "modules/core",
                                lockfile(List.of(), List.of(betaConflict), List.of()),
                                Set.of())));

        assertEquals(
                List.of(
                        new LockConflict(
                                shared,
                                "2.0.0",
                                List.of("1.0.0", "2.0.0"),
                                ConflictSelectionReason.DIRECT_DEPENDENCY,
                                Optional.of("alpha"),
                                Optional.empty(),
                                List.of("apps/api")),
                        new LockConflict(
                                shared,
                                "2.0.0",
                                List.of("1.0.0", "2.0.0"),
                                ConflictSelectionReason.DIRECT_DEPENDENCY,
                                Optional.of("beta"),
                                Optional.empty(),
                                List.of("modules/core"))),
                aggregated.conflicts());
    }

    @Test
    void workspaceProvidedCoordinateShadowsExternalSameCoordinateTransitive() throws IOException {
        Workspace workspace = workspace(List.of(
                new WorkspaceProjectEdge("apps/api", "modules/core", "compile", "com.acme:core")));
        PackageId core = new PackageId("com.acme", "core");

        ZoltLockfile aggregated = new WorkspaceLockfileAggregator().aggregate(
                workspace,
                List.of(new WorkspaceMemberResolveOutput(
                        "apps/api",
                        lockfile(
                                List.of(externalPackage(
                                        core,
                                        "2.8.7",
                                        false,
                                        List.of(),
                                        List.of())),
                                List.of(),
                                List.of()),
                        Set.of())));

        List<LockPackage> coreEntries = aggregated.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(core))
                .toList();
        assertEquals(1, coreEntries.size());
        LockPackage live = coreEntries.getFirst();
        assertEquals("workspace", live.source());
        assertEquals("0.1.0", live.version());
        assertEquals(
                List.of(new LockConflict(
                        core,
                        "0.1.0",
                        List.of("0.1.0", "2.8.7"),
                        ConflictSelectionReason.DIRECT_DEPENDENCY,
                        Optional.empty(),
                        Optional.of(LockArtifactVariant.defaultVariant()),
                        List.of("apps/api"))),
                aggregated.conflicts());
    }

    @Test
    void keepsExternalTransitiveWhenNoWorkspaceCoordinateCollision() throws IOException {
        Workspace workspace = workspace(List.of(
                new WorkspaceProjectEdge("apps/api", "modules/core", "compile", "com.acme:core")));
        PackageId library = new PackageId("com.example", "library");

        ZoltLockfile aggregated = new WorkspaceLockfileAggregator().aggregate(
                workspace,
                List.of(new WorkspaceMemberResolveOutput(
                        "apps/api",
                        lockfile(
                                List.of(externalPackage(
                                        library,
                                        "1.0.0",
                                        false,
                                        List.of(),
                                        List.of())),
                                List.of(),
                                List.of()),
                        Set.of())));

        LockPackage external = packageById(aggregated, "com.example", "library");
        assertEquals("1.0.0", external.version());
        assertEquals("central", external.source());
        assertEquals(List.of(), aggregated.conflicts());
    }

    @Test
    void keepsPerMemberClassifierVariantsDistinctInAggregatedLock() throws IOException {
        Workspace workspace = workspace(List.of(
                new WorkspaceProjectEdge("apps/api", "modules/core", "compile", "com.acme:core", true)));
        PackageId epoll = new PackageId("io.netty", "netty-transport-native-epoll");

        ZoltLockfile aggregated = new WorkspaceLockfileAggregator().aggregate(
                workspace,
                List.of(
                        new WorkspaceMemberResolveOutput(
                                "apps/api",
                                lockfile(
                                        List.of(classifiedExternalPackage(epoll, "4.1.100.Final", "linux-x86_64")),
                                        List.of(),
                                        List.of()),
                                Set.of()),
                        new WorkspaceMemberResolveOutput(
                                "apps/worker",
                                lockfile(
                                        List.of(classifiedExternalPackage(epoll, "4.1.100.Final", "osx-aarch_64")),
                                        List.of(),
                                        List.of()),
                                Set.of())));

        List<LockPackage> epollEntries = aggregated.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(epoll))
                .toList();
        assertEquals(2, epollEntries.size());
        LockPackage linux = epollEntries.stream()
                .filter(lockPackage -> lockPackage.jar().orElseThrow().endsWith("linux-x86_64.jar"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("apps/api"), linux.members());
        assertEquals("jar-linux-x86_64", linux.jarSha256().orElseThrow());
        LockPackage osx = epollEntries.stream()
                .filter(lockPackage -> lockPackage.jar().orElseThrow().endsWith("osx-aarch_64.jar"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("apps/worker"), osx.members());
        assertEquals("jar-osx-aarch_64", osx.jarSha256().orElseThrow());
        assertEquals(List.of(), aggregated.conflicts());
    }

    @Test
    void attributesClassifiedApiExportToTheExactVariant() throws IOException {
        Workspace workspace = workspace(List.of());
        PackageId epoll = new PackageId("io.netty", "netty-transport-native-epoll");
        LockArtifactVariant linux =
                new LockArtifactVariant("jar", Optional.of("linux-x86_64"));
        ZoltLockfile aggregated = new WorkspaceLockfileAggregator().aggregate(
                workspace,
                List.of(new WorkspaceMemberResolveOutput(
                        "modules/core",
                        lockfile(
                                List.of(
                                        classifiedExternalPackage(
                                                epoll, "4.1.100.Final", "linux-x86_64"),
                                        classifiedExternalPackage(
                                                epoll, "4.1.100.Final", "osx-aarch_64")),
                                List.of(),
                                List.of()),
                        Set.of(new WorkspaceExportedPackage(epoll, linux)))));

        LockPackage exported = aggregated.packages().stream()
                .filter(pkg -> LockArtifactVariant.of(pkg).equals(linux))
                .findFirst()
                .orElseThrow();
        LockPackage sibling = aggregated.packages().stream()
                .filter(pkg -> LockArtifactVariant.of(pkg).classifier().equals(Optional.of("osx-aarch_64")))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("modules/core"), exported.exportedBy());
        assertEquals(List.of(), sibling.exportedBy());
    }

    @Test
    void aggregatesClassifierVariantsByteStablyAcrossRuns() throws IOException {
        Workspace workspace = workspace(List.of(
                new WorkspaceProjectEdge("apps/api", "modules/core", "compile", "com.acme:core", true)));
        PackageId epoll = new PackageId("io.netty", "netty-transport-native-epoll");
        List<WorkspaceMemberResolveOutput> outputs = List.of(
                new WorkspaceMemberResolveOutput(
                        "apps/api",
                        lockfile(List.of(classifiedExternalPackage(epoll, "4.1.100.Final", "linux-x86_64")), List.of(), List.of()),
                        Set.of()),
                new WorkspaceMemberResolveOutput(
                        "apps/worker",
                        lockfile(List.of(classifiedExternalPackage(epoll, "4.1.100.Final", "osx-aarch_64")), List.of(), List.of()),
                        Set.of()));

        ZoltLockfileWriter writer = new ZoltLockfileWriter();
        String first = writer.write(new WorkspaceLockfileAggregator().aggregate(workspace, outputs));
        String second = writer.write(new WorkspaceLockfileAggregator().aggregate(workspace, outputs));

        assertEquals(first, second);
        assertTrue(first.contains("netty-transport-native-epoll-4.1.100.Final-linux-x86_64.jar"));
        assertTrue(first.contains("netty-transport-native-epoll-4.1.100.Final-osx-aarch_64.jar"));
    }

    @Test
    void keepsDifferentVersionsAcrossClassifiersWithoutFalseConflict() throws IOException {
        // The netty scenario: apps/api resolves the linux jar at 4.1.90, apps/worker the osx jar at 4.1.100.
        // Distinct classified artifacts mediate independently, so each keeps its OWN version and there is no
        // GA-level conflict between 4.1.90 and 4.1.100 (they never actually compete).
        Workspace workspace = workspace(List.of(
                new WorkspaceProjectEdge("apps/api", "modules/core", "compile", "com.acme:core", true)));
        PackageId epoll = new PackageId("io.netty", "netty-transport-native-epoll");

        ZoltLockfile aggregated = new WorkspaceLockfileAggregator().aggregate(
                workspace,
                List.of(
                        new WorkspaceMemberResolveOutput(
                                "apps/api",
                                lockfile(List.of(classifiedExternalPackage(epoll, "4.1.90.Final", "linux-x86_64")), List.of(), List.of()),
                                Set.of()),
                        new WorkspaceMemberResolveOutput(
                                "apps/worker",
                                lockfile(List.of(classifiedExternalPackage(epoll, "4.1.100.Final", "osx-aarch_64")), List.of(), List.of()),
                                Set.of())));

        List<LockPackage> epollEntries = aggregated.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(epoll))
                .toList();
        assertEquals(2, epollEntries.size());
        LockPackage linux = epollEntries.stream()
                .filter(lockPackage -> lockPackage.jar().orElseThrow().endsWith("linux-x86_64.jar"))
                .findFirst().orElseThrow();
        assertEquals("4.1.90.Final", linux.version());
        LockPackage osx = epollEntries.stream()
                .filter(lockPackage -> lockPackage.jar().orElseThrow().endsWith("osx-aarch_64.jar"))
                .findFirst().orElseThrow();
        assertEquals("4.1.100.Final", osx.version());
        assertEquals(List.of(), aggregated.conflicts());
    }

    @Test
    void canonicalizesByteIdenticalMirrorsAcrossScopes() throws IOException {
        Workspace workspace = workspace(List.of());
        PackageId library = new PackageId("com.example", "mirrored");

        ZoltLockfile aggregated = new WorkspaceLockfileAggregator().aggregate(
                workspace,
                List.of(
                        new WorkspaceMemberResolveOutput(
                                "apps/api",
                                lockfile(
                                        List.of(mirrorPackage(
                                                library,
                                                "corp-mirror",
                                                DependencyScope.COMPILE,
                                                "jar-sha",
                                                "pom-sha")),
                                        List.of(),
                                        List.of()),
                                Set.of()),
                        new WorkspaceMemberResolveOutput(
                                "apps/worker",
                                lockfile(
                                        List.of(mirrorPackage(
                                                library,
                                                "central",
                                                DependencyScope.RUNTIME,
                                                "jar-sha",
                                                "pom-sha")),
                                        List.of(),
                                        List.of()),
                                Set.of())));

        List<LockPackage> mirrored = aggregated.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(library))
                .toList();
        assertEquals(2, mirrored.size());
        assertEquals(List.of("central", "central"), mirrored.stream()
                .map(LockPackage::source)
                .toList());
    }

    @Test
    void classifiedExternalAttachmentSurvivesWorkspaceShadowingOfThePlainJar() throws IOException {
        // A member provides the plain com.acme:core jar; a transitive brings the com.acme:core:tests
        // classified attachment. Shadowing must drop only the plain external the member actually replaces —
        // the :tests attachment is a distinct artifact the member does not provide and must survive.
        Workspace workspace = workspace(List.of(
                new WorkspaceProjectEdge("apps/api", "modules/core", "compile", "com.acme:core")));
        PackageId core = new PackageId("com.acme", "core");

        ZoltLockfile aggregated = new WorkspaceLockfileAggregator().aggregate(
                workspace,
                List.of(new WorkspaceMemberResolveOutput(
                        "apps/api",
                        lockfile(
                                List.of(
                                        externalPackage(core, "2.8.7", false, List.of(), List.of()),
                                        classifiedExternalPackage(core, "2.8.7", "tests")),
                                List.of(),
                                List.of()),
                        Set.of())));

        List<LockPackage> coreEntries = aggregated.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(core))
                .toList();
        assertEquals(2, coreEntries.size());
        assertTrue(coreEntries.stream().anyMatch(lockPackage ->
                lockPackage.workspace().isPresent() && lockPackage.version().equals("0.1.0")));
        LockPackage testsAttachment = coreEntries.stream()
                .filter(lockPackage -> lockPackage.jar().map(jar -> jar.endsWith("tests.jar")).orElse(false))
                .findFirst().orElseThrow();
        assertEquals("central", testsAttachment.source());
        assertEquals("2.8.7", testsAttachment.version());
        // The plain external com.acme:core (the artifact the member DOES provide) is shadowed away.
        assertTrue(coreEntries.stream().noneMatch(lockPackage ->
                lockPackage.workspace().isEmpty()
                        && lockPackage.jar().map(jar -> jar.endsWith("/2.8.7.jar")).orElse(false)));
    }

    @Test
    void rejectsUnsupportedWorkspaceDependencyScope() throws IOException {
        Workspace workspace = workspace(List.of(
                new WorkspaceProjectEdge("apps/api", "modules/core", "custom", "com.acme:core")));

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> new WorkspaceLockfileAggregator().aggregate(
                        workspace,
                        List.of(new WorkspaceMemberResolveOutput(
                                "apps/api",
                                new ZoltLockfile(ZoltLockfile.CURRENT_VERSION, List.of(), List.of()),
                                Set.of()))));

        assertEquals("Unsupported workspace dependency scope `custom`.", exception.getMessage());
    }

    private static LockPackage mirrorPackage(
            PackageId packageId,
            String source,
            DependencyScope scope,
            String jarSha,
            String pomSha) {
        String base = "com/example/mirrored/1.0.0/mirrored-1.0.0";
        return new LockPackage(
                packageId,
                "1.0.0",
                source,
                scope,
                true,
                Optional.of(base + ".jar"),
                Optional.of(base + ".pom"),
                Optional.of(jarSha),
                Optional.of(pomSha),
                List.of());
    }
}
