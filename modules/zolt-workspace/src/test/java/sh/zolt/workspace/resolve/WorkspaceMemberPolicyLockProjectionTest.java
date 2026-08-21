package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockMemberGraph;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;

final class WorkspaceMemberPolicyLockProjectionTest {
    private static final PackageId LIB = new PackageId("com.example", "lib");
    private static final PackageId TEST_LIB = new PackageId("com.example", "test-lib");
    private static final PackageId UNRELATED = new PackageId("com.example", "unrelated");

    @TempDir
    private Path tempDir;

    @Test
    void retainsOnlyTheMembersExactAllScopeGraphPoliciesAndConflicts() throws IOException {
        WorkspaceMember app = member("apps/app", "app", """

                [runtime.dependencies]
                "com.example:lib" = { version = "2.0.0", classifier = "linux" }

                [test.dependencies]
                "com.example:test-lib" = "1.0.0"
                """);
        WorkspaceMember admin = member("apps/admin", "admin", "");
        Workspace workspace = new Workspace(
                tempDir,
                tempDir.resolve("zolt-workspace.toml"),
                new WorkspaceConfig(
                        "demo",
                        List.of("apps/app", "apps/admin"),
                        List.of(),
                        Map.of(),
                        Map.of()),
                List.of(app, admin),
                List.of());
        LockPackage runtime = external(
                LIB,
                "2.0.0",
                DependencyScope.RUNTIME,
                "linux",
                List.of("aggregate-policy"),
                List.of("apps/app"));
        LockPackage test = external(
                TEST_LIB,
                "1.0.0",
                DependencyScope.TEST,
                null,
                List.of("test-policy"),
                List.of("apps/app"));
        LockPackage unrelated = external(
                UNRELATED,
                "1.0.0",
                DependencyScope.COMPILE,
                null,
                List.of("unrelated-policy"),
                List.of("apps/admin"));
        ZoltLockfile aggregate = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.of("sha256:workspace"),
                List.of(),
                List.of(runtime, test, unrelated),
                List.of(
                        conflict(LIB, Optional.of(new LockArtifactVariant("jar", Optional.of("linux"))), "apps/app"),
                        conflict(UNRELATED, Optional.empty(), "apps/admin")),
                List.of(),
                List.of(new LockMemberGraph(
                        "apps/app",
                        LIB,
                        "2.0.0",
                        new LockArtifactVariant("jar", Optional.of("linux")),
                        DependencyScope.RUNTIME,
                        List.of(),
                        List.of("runtime-policy"),
                        false)));

        ZoltLockfile projected = new WorkspaceMemberPolicyLockProjection()
                .project("apps/app", app.config(), aggregate, workspace);

        assertEquals(List.of(LIB, TEST_LIB), projected.packages().stream()
                .map(LockPackage::packageId)
                .toList());
        assertEquals(List.of(DependencyScope.RUNTIME, DependencyScope.TEST), projected.packages().stream()
                .map(LockPackage::scope)
                .toList());
        assertTrue(projected.packages().stream().allMatch(LockPackage::direct));
        assertEquals(List.of("runtime-policy"), projected.packages().getFirst().policies());
        assertEquals(List.of(LIB), projected.conflicts().stream()
                .map(LockConflict::packageId)
                .toList());
        assertEquals(2, projected.memberGraphs().size());
        assertTrue(projected.memberGraphs().stream()
                .allMatch(graph -> graph.member().equals("apps/app")));
        assertEquals(
                List.of(
                        "com.example:lib:2.0.0:jar|linux:runtime",
                        "com.example:test-lib:1.0.0:jar:test"),
                new WorkspaceMemberGraphRoots()
                        .roots("apps/app", app.config(), aggregate, workspace));
    }

    @Test
    void includesInjectedToolClosureRootsWithoutCallingThemDirect() throws IOException {
        WorkspaceMember app = member("apps/app", "app", "");
        Workspace workspace = new Workspace(
                tempDir,
                tempDir.resolve("zolt-workspace.toml"),
                new WorkspaceConfig("demo", List.of("apps/app"), List.of(), Map.of(), Map.of()),
                List.of(app),
                List.of());
        LockPackage leaf = tool("args4j", "args4j", "2.0.0", List.of());
        LockPackage cli = tool(
                "org.jacoco",
                "org.jacoco.cli",
                "0.8.14",
                List.of("args4j:args4j:2.0.0:jar:tool-coverage"));
        LockPackage agent = tool("org.jacoco", "org.jacoco.agent", "0.8.14", List.of());
        ZoltLockfile aggregate = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION, List.of(cli, leaf, agent), List.of());

        WorkspaceMemberPolicyLockProjection projection = new WorkspaceMemberPolicyLockProjection();

        assertEquals(
                List.of(
                        "org.jacoco:org.jacoco.agent:0.8.14:jar:tool-coverage",
                        "org.jacoco:org.jacoco.cli:0.8.14:jar:tool-coverage"),
                new WorkspaceMemberGraphRoots().roots("apps/app", app.config(), aggregate, workspace));
        assertTrue(projection.project("apps/app", app.config(), aggregate, workspace).packages().stream()
                .noneMatch(LockPackage::direct));
    }

    @Test
    void preservesOnlyTheSelectedMembersDependencyRoots() throws IOException {
        WorkspaceMember app = member("apps/app", "app", "");
        WorkspaceMember admin = member("apps/admin", "admin", "");
        Workspace workspace = new Workspace(
                tempDir,
                tempDir.resolve("zolt-workspace.toml"),
                new WorkspaceConfig(
                        "demo", List.of("apps/app", "apps/admin"), List.of(), Map.of(), Map.of()),
                List.of(app, admin),
                List.of());
        LockDependencyRoot selected = new LockDependencyRoot(
                "apps/app",
                LIB,
                "4.0.0",
                new LockArtifactVariant("jar", Optional.of("tests")),
                DependencyLane.API,
                Optional.empty(),
                true,
                true);
        LockDependencyRoot unrelated = new LockDependencyRoot(
                "apps/admin",
                UNRELATED,
                "5.0.0",
                null,
                DependencyLane.IMPLEMENTATION,
                Optional.empty(),
                false,
                true);
        ZoltLockfile aggregate = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(selected, unrelated));

        ZoltLockfile projected = new WorkspaceMemberPolicyLockProjection()
                .project("apps/app", app.config(), aggregate, workspace);

        assertEquals(List.of(selected), projected.dependencyRoots());
    }

    private WorkspaceMember member(
            String path,
            String name,
            String body) throws IOException {
        Path directory = tempDir.resolve(path);
        Files.createDirectories(directory);
        Path config = directory.resolve("zolt.toml");
        Files.writeString(config, """
                [project]
                name = "%s"
                version = "1.0.0"
                group = "com.example"
                java = "21"
                %s
                """.formatted(name, body));
        ProjectConfig parsed = new ZoltTomlParser().parse(config);
        return new WorkspaceMember(path, directory, parsed);
    }

    private static LockPackage external(
            PackageId packageId,
            String version,
            DependencyScope scope,
            String classifier,
            List<String> policies,
            List<String> members) {
        String suffix = classifier == null ? "" : "-" + classifier;
        String base = packageId.groupId().replace('.', '/')
                + "/"
                + packageId.artifactId()
                + "/"
                + version
                + "/"
                + packageId.artifactId()
                + "-"
                + version;
        return new LockPackage(
                packageId,
                version,
                "central",
                scope,
                true,
                Optional.of(base + suffix + ".jar"),
                Optional.of(base + ".pom"),
                Optional.of("jar-sha" + suffix),
                Optional.of("pom-sha"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                members,
                List.of(),
                policies,
                List.of());
    }

    private static LockConflict conflict(
            PackageId packageId,
            Optional<LockArtifactVariant> variant,
            String member) {
        return new LockConflict(
                packageId,
                "2.0.0",
                List.of("1.0.0", "2.0.0"),
                ConflictSelectionReason.DIRECT_DEPENDENCY,
                Optional.empty(),
                variant,
                List.of(member));
    }

    private static LockPackage tool(
            String group,
            String artifact,
            String version,
            List<String> dependencies) {
        return new LockPackage(
                new PackageId(group, artifact),
                version,
                "central",
                DependencyScope.TOOL_COVERAGE,
                false,
                Optional.of(group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + ".jar"),
                Optional.empty(),
                Optional.of("jar-sha"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                dependencies,
                List.of("apps/app"),
                List.of(),
                List.of(),
                List.of());
    }
}
