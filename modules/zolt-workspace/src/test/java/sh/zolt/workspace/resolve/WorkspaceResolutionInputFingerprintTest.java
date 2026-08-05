package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceInputs;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceProjectEdge;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every class of resolution input has to flip the fingerprint, because a fingerprint that misses one
 * lets a stale lock through the freshness gate unchecked.
 */
final class WorkspaceResolutionInputFingerprintTest {
    private static final Path ROOT = Path.of("/workspace/demo");
    private static final String LIB = """
            [project]
            name = "lib"
            version = "0.1.0"
            group = "com.example"
            java = "21"

            [dependencies]
            "org.slf4j:slf4j-api" = "2.0.17"
            """;
    private static final String APP = """
            [project]
            name = "app"
            version = "0.1.0"
            group = "com.example"
            java = "21"

            [dependencies]
            "com.example:lib" = { workspace = "lib" }
            """;

    @Test
    void isStableAcrossRepeatedComputation() {
        Workspace workspace = workspace(defaultWorkspaceConfig(), Map.of("lib", LIB, "app", APP));

        assertEquals(
                fingerprint(workspace),
                fingerprint(workspace));
    }

    @Test
    void isStableAcrossMemberAndEdgeOrdering() {
        Workspace ordered = workspace(defaultWorkspaceConfig(), Map.of("lib", LIB, "app", APP));
        Workspace reversed = new Workspace(
                ordered.root(),
                ordered.configPath(),
                ordered.config(),
                reversed(ordered.members()),
                reversed(ordered.edges()),
                reversed(ordered.buildOrder()),
                ordered.inputs());

        assertEquals(fingerprint(ordered), fingerprint(reversed));
    }

    @Test
    void changesWhenAMemberIsRemovedFromTheMemberList() {
        Workspace both = workspace(defaultWorkspaceConfig(), Map.of("lib", LIB, "app", APP));
        Workspace onlyLib = workspace(
                """
                [workspace]
                name = "demo"
                members = ["lib"]
                """,
                Map.of("lib", LIB));

        assertNotEquals(fingerprint(both), fingerprint(onlyLib));
    }

    @Test
    void changesWhenAMemberCoordinateChanges() {
        Workspace before = workspace(defaultWorkspaceConfig(), Map.of("lib", LIB, "app", APP));
        Workspace after = workspace(
                defaultWorkspaceConfig(),
                Map.of("lib", LIB.replace("group = \"com.example\"", "group = \"com.other\""), "app", APP));

        assertNotEquals(fingerprint(before), fingerprint(after));
    }

    @Test
    void changesWhenAMemberVersionChanges() {
        Workspace before = workspace(defaultWorkspaceConfig(), Map.of("lib", LIB, "app", APP));
        Workspace after = workspace(
                defaultWorkspaceConfig(),
                Map.of("lib", LIB.replace("version = \"0.1.0\"", "version = \"0.2.0\""), "app", APP));

        assertNotEquals(fingerprint(before), fingerprint(after));
    }

    @Test
    void changesWhenAMemberDependencyChanges() {
        Workspace before = workspace(defaultWorkspaceConfig(), Map.of("lib", LIB, "app", APP));
        Workspace after = workspace(
                defaultWorkspaceConfig(),
                Map.of("lib", LIB.replace("2.0.17", "2.0.16"), "app", APP));

        assertNotEquals(fingerprint(before), fingerprint(after));
    }

    @Test
    void changesWhenWorkspacePolicyMergingChangesAMemberEffectiveConfig() {
        Workspace before = workspace(defaultWorkspaceConfig(), Map.of("lib", LIB, "app", APP));
        Workspace after = workspace(
                """
                [workspace]
                name = "demo"
                members = ["lib", "app"]

                [platforms]
                "com.example:platform" = "1.0.0"
                """,
                Map.of("lib", LIB, "app", APP));

        assertNotEquals(fingerprint(before), fingerprint(after));
        assertNotEquals(
                ProjectResolutionFingerprints.of(before, "lib"),
                ProjectResolutionFingerprints.of(after, "lib"),
                "the platform must reach the member's effective config, not only the workspace bytes");
    }

    @Test
    void changesWhenAWorkspaceRepositoryChanges() {
        Workspace before = workspace(
                """
                [workspace]
                name = "demo"
                members = ["lib", "app"]

                [repositories]
                internal = "https://repo.example/internal"
                """,
                Map.of("lib", LIB, "app", APP));
        Workspace after = workspace(
                """
                [workspace]
                name = "demo"
                members = ["lib", "app"]

                [repositories]
                internal = "https://repo.example/mirror"
                """,
                Map.of("lib", LIB, "app", APP));

        assertNotEquals(fingerprint(before), fingerprint(after));
    }

    @Test
    void changesWhenAWorkspaceEdgeScopeChanges() {
        Workspace before = workspace(defaultWorkspaceConfig(), Map.of("lib", LIB, "app", APP));
        Workspace after = new Workspace(
                before.root(),
                before.configPath(),
                before.config(),
                before.members(),
                before.edges().stream()
                        .map(edge -> new WorkspaceProjectEdge(
                                edge.from(), edge.to(), "test", edge.coordinate(), edge.exported(), edge.optional()))
                        .toList(),
                before.buildOrder(),
                before.inputs());

        assertNotEquals(fingerprint(before), fingerprint(after));
    }

    @Test
    void changesWhenAnAbsentOptionalConfigPathAppears() {
        Workspace absent = workspace(defaultWorkspaceConfig(), Map.of("lib", LIB, "app", APP));
        Workspace present = absent.withInputs(
                absent.inputs().withContent(
                        ROOT.resolve("zolt-workspace.toml"),
                        defaultWorkspaceConfig().getBytes(StandardCharsets.UTF_8)));

        assertNotEquals(fingerprint(absent), fingerprint(present));
    }

    @Test
    void changesWhenACommentOnlyEditChangesConfigBytes() {
        Workspace before = workspace(defaultWorkspaceConfig(), Map.of("lib", LIB, "app", APP));
        Workspace after = workspace(
                defaultWorkspaceConfig(),
                Map.of("lib", LIB + "\n# harmless\n", "app", APP));

        assertNotEquals(
                fingerprint(before),
                fingerprint(after),
                "config bytes are hashed, so a comment edit conservatively invalidates");
    }

    @Test
    void doesNotDependOnWhereTheWorkspaceIsCheckedOut() {
        Workspace here = workspace(defaultWorkspaceConfig(), Map.of("lib", LIB, "app", APP));
        Workspace elsewhere = relocated(here, Path.of("/elsewhere/checkout"));

        assertEquals(fingerprint(here), fingerprint(elsewhere));
    }

    @Test
    void isAbsentWhenNoConfigBytesWereCaptured() {
        Workspace workspace = workspace(defaultWorkspaceConfig(), Map.of("lib", LIB, "app", APP))
                .withInputs(WorkspaceInputs.unchecked());

        assertTrue(WorkspaceResolutionInputFingerprint.fingerprint(workspace).isEmpty());
    }

    @Test
    void isPrefixedWithItsDigestAlgorithm() {
        assertTrue(fingerprint(workspace(defaultWorkspaceConfig(), Map.of("lib", LIB)))
                .startsWith("sha256:"));
    }

    private static String defaultWorkspaceConfig() {
        return """
                [workspace]
                name = "demo"
                members = ["lib", "app"]
                """;
    }

    private static String fingerprint(Workspace workspace) {
        return WorkspaceResolutionInputFingerprint.fingerprint(workspace).orElseThrow();
    }

    private static Workspace relocated(Workspace workspace, Path root) {
        Map<Path, byte[]> files = new LinkedHashMap<>();
        workspace.inputs().digestsRelativeTo(workspace.root()).keySet().forEach(relative ->
                workspace.inputs()
                        .contentBytes(workspace.root().resolve(relative))
                        .ifPresent(content -> files.put(root.resolve(relative), content)));
        List<WorkspaceMember> members = workspace.members().stream()
                .map(member -> new WorkspaceMember(
                        member.path(), root.resolve(member.path()), member.config()))
                .toList();
        return new Workspace(
                root,
                root.resolve(workspace.root().relativize(workspace.configPath())),
                workspace.config(),
                members,
                workspace.edges(),
                workspace.buildOrder(),
                WorkspaceInputs.captured(files, Set.of(root.resolve("zolt-workspace.toml"))));
    }

    private static <T> List<T> reversed(List<T> values) {
        List<T> copy = new ArrayList<>(values);
        java.util.Collections.reverse(copy);
        return copy;
    }

    /**
     * Mirrors the shape workspace discovery produces: exact config bytes for the workspace file and
     * every member, plus edges derived from the members' declared workspace dependencies.
     */
    private static Workspace workspace(
            String workspaceToml,
            Map<String, String> memberTomls) {
        ZoltTomlParser parser = new ZoltTomlParser();
        Path configPath = ROOT.resolve("zolt.toml");
        Map<Path, byte[]> files = new LinkedHashMap<>();
        files.put(configPath, workspaceToml.getBytes(StandardCharsets.UTF_8));
        List<WorkspaceMember> members = new ArrayList<>();
        List<String> declared = declaredMembers(workspaceToml);
        for (String path : declared) {
            String toml = memberTomls.get(path);
            if (toml == null) {
                continue;
            }
            files.put(ROOT.resolve(path).resolve("zolt.toml"), toml.getBytes(StandardCharsets.UTF_8));
            members.add(new WorkspaceMember(path, ROOT.resolve(path), parser.parse(toml)));
        }
        return new Workspace(
                ROOT,
                configPath,
                new sh.zolt.workspace.toml.WorkspaceConfigParser().parseRootConfig(workspaceToml),
                members,
                edges(members),
                declared,
                WorkspaceInputs.captured(files, Set.of(ROOT.resolve("zolt-workspace.toml"))));
    }

    private static List<String> declaredMembers(String workspaceToml) {
        WorkspaceConfig config =
                new sh.zolt.workspace.toml.WorkspaceConfigParser().parseRootConfig(workspaceToml);
        return config.members();
    }

    private static List<WorkspaceProjectEdge> edges(List<WorkspaceMember> members) {
        Map<String, WorkspaceMember> byCoordinate = new LinkedHashMap<>();
        members.forEach(member -> byCoordinate.put(
                member.config().project().group() + ":" + member.config().project().name(), member));
        List<WorkspaceProjectEdge> edges = new ArrayList<>();
        for (WorkspaceMember member : members) {
            ProjectConfig config = member.config();
            config.workspaceDependencies().forEach((coordinate, target) -> {
                WorkspaceMember to = byCoordinate.get(coordinate);
                if (to != null) {
                    edges.add(new WorkspaceProjectEdge(
                            member.path(), to.path(), "compile", coordinate, false, false));
                }
            });
        }
        return edges;
    }

    /** Exposes one member's effective-config fingerprint so a policy-merge test can be specific. */
    private static final class ProjectResolutionFingerprints {
        private ProjectResolutionFingerprints() {
        }

        static Optional<String> of(Workspace workspace, String memberPath) {
            return Optional.ofNullable(
                            WorkspaceResolutionInputFingerprint.effectiveConfigs(workspace).get(memberPath))
                    .map(sh.zolt.resolve.fingerprint.ProjectResolutionFingerprint::fingerprint);
        }
    }
}
