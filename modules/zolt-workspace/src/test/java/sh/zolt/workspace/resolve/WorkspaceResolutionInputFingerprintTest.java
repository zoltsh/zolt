package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.manifest.adapter.EffectiveProjectConfigAdapter;
import sh.zolt.manifest.effective.EffectiveManifestComposer;
import sh.zolt.manifest.effective.EffectiveWorkspace;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.RepositorySettings;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
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
            java = 21

            [dependencies]
            "org.slf4j:slf4j-api" = "2.0.17"
            """;
    private static final String APP = """
            [project]
            name = "app"
            version = "0.1.0"
            group = "com.example"
            java = 21

            [dependencies]
            "com.example:lib" = { workspace = true }
            """;
    private static final String LOCK = """
            version = 7
            projectResolutionFingerprint = "sha256:abc"

            [[dependencyRoot]]
            member = "lib"
            id = "org.slf4j:slf4j-api"
            version = "2.0.17"
            lane = "implementation"
            resolvedScope = "compile"

            [[package]]
            id = "org.slf4j:slf4j-api"
            version = "2.0.17"
            source = "maven-central"
            scope = "compile"
            direct = true
            members = ["lib"]
            dependencies = []
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

                [workspace.members]
                include = ["lib"]
                """,
                Map.of("lib", LIB));

        assertNotEquals(fingerprint(both), fingerprint(onlyLib));
    }

    @Test
    void changesWhenAMemberCoordinateChanges() {
        Workspace before = workspace(defaultWorkspaceConfig(), Map.of("lib", LIB, "app", APP));
        Workspace after = workspace(
                defaultWorkspaceConfig(),
                Map.of(
                        "lib", LIB.replace("group = \"com.example\"", "group = \"com.other\""),
                        "app", APP.replace("com.example:lib", "com.other:lib")));

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

                [workspace.members]
                include = ["lib", "app"]

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

    /**
     * The workspace fingerprint digests raw manifest bytes, so it would notice a classifier edit even
     * if nothing structured did. That coarse leg is not enough on its own: the per-member
     * {@code memberResolution} leg is what carries a member's effective configuration into the
     * workspace lock, so the variant has to reach it too. Both spellings below are non-default
     * variants, so only the variant itself distinguishes them.
     */
    @Test
    void classifierChangeStalesWorkspaceLock() {
        String linux = LIB.replace(
                "\"org.slf4j:slf4j-api\" = \"2.0.17\"",
                "\"io.netty:netty-transport-native-epoll\" = "
                        + "{ version = \"4.1.119.Final\", classifier = \"linux-x86_64\" }");
        String macos = linux.replace("linux-x86_64", "osx-aarch64");
        Workspace before = workspace(defaultWorkspaceConfig(), Map.of("lib", linux, "app", APP));
        Workspace after = workspace(defaultWorkspaceConfig(), Map.of("lib", macos, "app", APP));

        assertNotEquals(fingerprint(before), fingerprint(after));
        assertNotEquals(
                ProjectResolutionFingerprints.of(before, "lib"),
                ProjectResolutionFingerprints.of(after, "lib"),
                "the classifier must reach the member's effective config, not only the workspace bytes");
    }

    @Test
    void changesWhenAWorkspaceRepositoryChanges() {
        Workspace before = workspace(
                """
                [workspace]
                name = "demo"

                [workspace.members]
                include = ["lib", "app"]

                [repositories]
                central = false

                [repositories.internal]
                url = "https://repo.example/internal"
                """,
                Map.of("lib", LIB, "app", APP));
        Workspace after = workspace(
                """
                [workspace]
                name = "demo"

                [workspace.members]
                include = ["lib", "app"]

                [repositories]
                central = false

                [repositories.internal]
                url = "https://repo.example/mirror"
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
                        ROOT.resolve("zolt.toml"),
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

        assertTrue(WorkspaceResolutionInputFingerprint.fingerprint(workspace, LOCK).isEmpty());
    }

    @Test
    void isPrefixedWithItsDigestAlgorithm() {
        assertTrue(fingerprint(workspace(defaultWorkspaceConfig(), Map.of("lib", LIB)))
                .startsWith("sha256:"));
    }

    @Test
    void changesWhenTheLockItCertifiesChanges() {
        Workspace workspace = workspace(defaultWorkspaceConfig(), Map.of("lib", LIB, "app", APP));

        assertNotEquals(
                fingerprint(workspace),
                WorkspaceResolutionInputFingerprint
                        .fingerprint(workspace, LOCK.replace("2.0.17", "2.0.16"))
                        .orElseThrow());
    }

    /** Otherwise recording the value would change the digest that produced it. */
    @Test
    void ignoresTheFingerprintTheLockAlreadyRecords() {
        Workspace workspace = workspace(defaultWorkspaceConfig(), Map.of("lib", LIB, "app", APP));
        String recorded = LOCK.replace(
                "projectResolutionFingerprint = \"sha256:abc\"",
                "projectResolutionFingerprint = \"sha256:abc\"\n"
                        + "workspaceResolutionInputFingerprint = \"sha256:whatever\"");

        assertEquals(
                fingerprint(workspace),
                WorkspaceResolutionInputFingerprint.fingerprint(workspace, recorded).orElseThrow());
    }

    /** Toolchain blocks are a sidecar the dependency lock does not own. */
    @Test
    void ignoresJavaToolchainBlocksInTheLock() {
        Workspace workspace = workspace(defaultWorkspaceConfig(), Map.of("lib", LIB, "app", APP));
        String withToolchain = LOCK + """

                [[toolchain.java]]
                version = "21"
                """;

        assertEquals(
                fingerprint(workspace),
                WorkspaceResolutionInputFingerprint
                        .fingerprint(workspace, withToolchain)
                        .orElseThrow());
    }

    private static String defaultWorkspaceConfig() {
        return """
                [workspace]
                name = "demo"

                [workspace.members]
                include = ["lib", "app"]
                """;
    }

    private static String fingerprint(Workspace workspace) {
        return WorkspaceResolutionInputFingerprint.fingerprint(workspace, LOCK).orElseThrow();
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
                WorkspaceInputs.captured(files, Set.of(root.resolve("zolt.toml"))));
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
        ManifestProjectConfigLoader manifestLoader = new ManifestProjectConfigLoader();
        Path configPath = ROOT.resolve("zolt.toml");
        Map<Path, byte[]> files = new LinkedHashMap<>();
        files.put(configPath, workspaceToml.getBytes(StandardCharsets.UTF_8));
        AuthoredManifest root = manifestLoader.document(workspaceToml).authored();
        Map<WorkspaceMemberPath, AuthoredManifest> authoredMembers = new LinkedHashMap<>();
        List<String> declared = declaredMembers(workspaceToml);
        for (String path : declared) {
            String toml = memberTomls.get(path);
            if (toml == null) {
                continue;
            }
            files.put(ROOT.resolve(path).resolve("zolt.toml"), toml.getBytes(StandardCharsets.UTF_8));
            authoredMembers.put(
                    new WorkspaceMemberPath(path), manifestLoader.document(toml).authored());
        }
        EffectiveWorkspace effective =
                new EffectiveManifestComposer().composeWorkspace(root, authoredMembers);
        EffectiveProjectConfigAdapter adapter = new EffectiveProjectConfigAdapter();
        List<WorkspaceMember> members = new ArrayList<>();
        for (String path : declared) {
            WorkspaceMemberPath memberPath = new WorkspaceMemberPath(path);
            if (!authoredMembers.containsKey(memberPath)) {
                continue;
            }
            members.add(new WorkspaceMember(
                    path,
                    ROOT.resolve(path),
                    adapter.adapt(
                            effective.members().get(memberPath),
                            EffectiveProjectConfigAdapter.workspacePaths(effective, memberPath))));
        }
        return new Workspace(
                ROOT,
                configPath,
                workspaceConfig(workspaceToml),
                members,
                edges(members),
                declared,
                WorkspaceInputs.captured(files, Set.of(ROOT.resolve("zolt.toml"))));
    }

    private static List<String> declaredMembers(String workspaceToml) {
        return workspaceConfig(workspaceToml).members();
    }

    /**
     * The legacy workspace view of one final root manifest. Includes are exact member paths in these
     * fixtures, so the final member set is the include list itself.
     */
    private static WorkspaceConfig workspaceConfig(String workspaceToml) {
        AuthoredManifest authored = new ManifestProjectConfigLoader()
                .document(workspaceToml)
                .authored();
        AuthoredWorkspace workspace = authored.workspace().orElseThrow();
        Map<String, RepositorySettings> repositorySettings = new LinkedHashMap<>();
        authored.repositories()
                .map(AuthoredDependencyRepositories::named)
                .orElseGet(Map::of)
                .forEach((id, repository) -> repositorySettings.put(
                        id.value(),
                        new RepositorySettings(
                                id.value(),
                                repository.url().value(),
                                repository.credentials().map(LocalId::value))));
        Map<String, String> repositories = new LinkedHashMap<>();
        repositorySettings.forEach((id, settings) -> repositories.put(id, settings.url()));
        Map<String, String> platforms = new LinkedHashMap<>();
        authored.platforms()
                .map(AuthoredPlatforms::entries)
                .orElseGet(Map::of)
                .forEach((coordinate, selector) -> platforms.put(
                        coordinate.value(),
                        ((PlatformSelector.FixedVersion) selector).value()));
        return new WorkspaceConfig(
                workspace.name().value(),
                workspace.members().include().stream()
                        .map(WorkspaceMemberPattern::value)
                        .toList(),
                workspace.members().defaultMembers()
                        .map(paths -> paths.stream().map(WorkspaceMemberPath::value).toList())
                        .orElseGet(List::of),
                Map.copyOf(repositories),
                Map.copyOf(platforms),
                Map.copyOf(repositorySettings),
                Map.of());
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
