package sh.zolt.toml.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestSemanticTestSupport.decodeAuthoredManifest;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.adapter.EffectiveProjectConfigAdapter;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.effective.EffectiveManifest;
import sh.zolt.manifest.effective.EffectiveManifestComposer;
import sh.zolt.manifest.effective.EffectiveWorkspace;
import sh.zolt.toml.ZoltConfigException;

/**
 * Design §21: there is one validity boundary. Every manifest the parser and effective composition
 * accept must adapt onto the legacy engine model, so the adapter is total and any failure inside it
 * is internal schema drift rather than a user error.
 */
final class ManifestAdapterTotalityTest {
    private static final String GOLDEN_ROOT = "/golden/manifest-language/";
    private static final EffectiveManifestComposer COMPOSER = new EffectiveManifestComposer();
    private static final EffectiveProjectConfigAdapter ADAPTER = new EffectiveProjectConfigAdapter();

    /** How each canonical fixture is composed before it reaches the adapter. */
    private enum Composition {
        STANDALONE,
        WORKSPACE_ROOT,
        WORKSPACE_MEMBER
    }

    private static final Map<String, Composition> CORPUS = Map.of(
            "standalone-application.toml", Composition.STANDALONE,
            "library-api-boundary.toml", Composition.STANDALONE,
            "spring-boot-service.toml", Composition.STANDALONE,
            "central-ready-library.toml", Composition.STANDALONE,
            "enterprise-repository.toml", Composition.STANDALONE,
            "root-project-workspace.toml", Composition.WORKSPACE_ROOT,
            "virtual-workspace.toml", Composition.WORKSPACE_ROOT,
            "workspace-member.toml", Composition.WORKSPACE_MEMBER,
            "workspace-bom-member.toml", Composition.WORKSPACE_MEMBER);

    @Test
    void theCorpusCoversEveryCanonicalFixture() throws IOException, URISyntaxException {
        try (var paths = Files.list(resourceDirectory())) {
            assertEquals(
                    new TreeSet<>(CORPUS.keySet()),
                    paths.map(path -> path.getFileName().toString())
                            .collect(java.util.stream.Collectors.toCollection(TreeSet::new)));
        }
    }

    @Test
    void allAcceptedManifestFixturesAdaptSuccessfully() throws IOException {
        for (String name : new TreeSet<>(CORPUS.keySet())) {
            if (CORPUS.get(name) == Composition.WORKSPACE_MEMBER) {
                continue;
            }
            AuthoredManifest authored = golden(name);
            assertDoesNotThrow(() -> {
                if (CORPUS.get(name) == Composition.STANDALONE) {
                    ADAPTER.adapt(COMPOSER.composeStandalone(authored));
                } else {
                    adaptWorkspaceRoot(authored);
                }
            }, name);
        }
        assertDoesNotThrow(ManifestAdapterTotalityTest::adaptMemberFixtureWorkspace, "member fixtures");
    }

    /** The member fixtures are composed together in one workspace that provides their siblings. */
    private static void adaptMemberFixtureWorkspace() throws IOException {
        AuthoredManifest root = golden("virtual-workspace.toml");
        Map<WorkspaceMemberPath, AuthoredManifest> members = new java.util.LinkedHashMap<>();
        members.put(new WorkspaceMemberPath("apps/zolt"), stubMember("zolt"));
        members.put(new WorkspaceMemberPath("apps/admin"), stubMember("admin"));
        members.put(new WorkspaceMemberPath("modules/zolt-model"), stubMember("zolt-model"));
        members.put(new WorkspaceMemberPath("modules/zolt-toml"), golden("workspace-member.toml"));
        members.put(
                new WorkspaceMemberPath("modules/platform-bom"),
                golden("workspace-bom-member.toml"));

        EffectiveWorkspace effective = COMPOSER.composeWorkspace(root, members);

        effective.members().forEach((path, manifest) -> ADAPTER.adapt(
                manifest, EffectiveProjectConfigAdapter.workspacePaths(effective, path)));
    }

    private static AuthoredManifest stubMember(String name) {
        return decodeAuthoredManifest("[project]\nname = \"" + name + "\"\n");
    }

    /**
     * Every source in {@link #adapterTripHazards} is accepted by the parser and by effective
     * composition, so the adapter must accept it too — or the parser must have rejected it first.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("adapterTripHazards")
    void everyManifestTheParserAcceptsAlsoAdapts(String description, String source) {
        AuthoredManifest authored;
        try {
            authored = decodeAuthoredManifest(source);
        } catch (ZoltConfigException rejectedByTheParser) {
            assertTrue(
                    rejectedByTheParser.getMessage().contains("Invalid"),
                    () -> description + ": " + rejectedByTheParser.getMessage());
            return;
        }
        EffectiveManifest effective = COMPOSER.composeStandalone(authored);

        assertDoesNotThrow(() -> ADAPTER.adapt(effective), description);
    }

    private static List<Arguments> adapterTripHazards() {
        return List.of(
                Arguments.of("blank JVM argument", runtime("jvmArgs = [\"-ea\", \"  \"]")),
                Arguments.of("reserved user.dir property",
                        runtime("properties = { \"user.dir\" = \"/tmp\" }")),
                Arguments.of("reserved java.class.path property",
                        runtime("properties = { \"java.class.path\" = \"x\" }")),
                Arguments.of("blank property name", runtime("properties = { \"  \" = \"value\" }")),
                Arguments.of("whitespace-padded property name",
                        runtime("properties = { \" mode \" = \"value\" }")),
                Arguments.of("blank property value", runtime("properties = { mode = \"  \" }")),
                Arguments.of("blank environment value", runtime("env = { MODE = \"  \" }")),
                Arguments.of("whitespace-bearing suite tag", suite("tags = [\"fast tag\"]")),
                Arguments.of("comma-bearing suite tag", suite("tags = [\"fast,slow\"]")),
                Arguments.of("whitespace-bearing exclude tag", suite("excludeTags = [\"slow tag\"]")),
                Arguments.of("whitespace-bearing suite pattern", suite("classes = [\"*Smoke Test\"]")),
                Arguments.of("whitespace-bearing exclude pattern",
                        suite("excludeClasses = [\"*Flaky Test\"]")),
                Arguments.of("credentialed loopback HTTP repository", project("""
                        [repositories.local]
                        url = "http://localhost:8081/repo"
                        credentials = "local"

                        [credentials.local]
                        usernameEnv = "REPO_USER"
                        passwordEnv = "REPO_TOKEN"
                        """)),
                Arguments.of("blank task argument", project("""
                        [tasks.demo]
                        run = ["zolt", "  "]
                        """)),
                Arguments.of("blank alias argument", project("""
                        [aliases]
                        demo = ["build", "  "]
                        """)),
                Arguments.of("blank native argument", project("""
                        [native]
                        args = ["  "]
                        """)),
                Arguments.of("blank compiler argument", project("""
                        [compiler]
                        args = ["  "]
                        """)),
                Arguments.of("blank test compiler argument", project("""
                        [compiler.test]
                        args = ["  "]
                        """)),
                Arguments.of("blank manifest attribute value", project("""
                        [package.manifest]
                        "X-Build" = "  "
                        """)),
                Arguments.of("blank generated tool version command", project("""
                        [generated.tools.demo]
                        kind = "process"
                        binary = "demo"
                        versionCommand = ["demo", "  "]
                        allowUnpinnedTool = true
                        """)),
                Arguments.of("blank scm connection", project("""
                        [project.scm]
                        url = "https://example.com/demo"
                        connection = "  "
                        """)));
    }

    /** Each moved constraint now fails at its exact authored field rather than inside the adapter. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("focusedDiagnostics")
    void movedConstraintsFailAtTheirExactAuthoredField(String path, String source) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decodeAuthoredManifest(source), path);

        assertTrue(failure.getMessage().contains("`" + path + "`"), failure.getMessage());
    }

    private static List<Arguments> focusedDiagnostics() {
        return List.of(
                Arguments.of("test.runtime.jvmArgs[0]", runtime("jvmArgs = [\"  \"]")),
                Arguments.of("test.runtime.properties",
                        runtime("properties = { \"user.dir\" = \"/tmp\" }")),
                Arguments.of("test.runtime.env", runtime("env = { MODE = \"  \" }")),
                Arguments.of("test.suites.smoke.tags[0]", suite("tags = [\"fast tag\"]")),
                Arguments.of("test.suites.smoke.excludeTags[0]", suite("excludeTags = [\"a,b\"]")),
                Arguments.of("test.suites.smoke.classes[0]", suite("classes = [\"*Smoke Test\"]")),
                Arguments.of("test.suites.smoke.excludeClasses[0]",
                        suite("excludeClasses = [\"*Flaky Test\"]")));
    }

    /** A virtual root has no project of its own, so it is composed with a stand-in default member. */
    private static void adaptWorkspaceRoot(AuthoredManifest root) {
        boolean rootProject = root.project().isPresent();
        WorkspaceMemberPath member = rootProject
                ? new WorkspaceMemberPath(".")
                : root.workspace().orElseThrow().members().defaultMembers()
                        .map(List::getFirst)
                        .orElseGet(() -> new WorkspaceMemberPath("modules/member"));
        AuthoredManifest memberManifest = rootProject
                ? root
                : decodeAuthoredManifest("""
                        [project]
                        name = "member"
                        """);
        EffectiveWorkspace effective = COMPOSER.composeWorkspace(root, Map.of(member, memberManifest));
        effective.members().forEach((path, manifest) -> ADAPTER.adapt(
                manifest, EffectiveProjectConfigAdapter.workspacePaths(effective, path)));
    }

    private static String runtime(String field) {
        return """
                [project]
                name = "demo"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [test.runtime]
                """ + field + "\n";
    }

    private static String project(String section) {
        return """
                [project]
                name = "demo"
                version = "1.0.0"
                group = "com.example"
                java = 21

                """ + section;
    }

    private static String suite(String field) {
        return """
                [project]
                name = "demo"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [test.suites.smoke]
                """ + field + "\n";
    }

    private static AuthoredManifest golden(String resourceName) throws IOException {
        return decodeAuthoredManifest(FinalManifests.goldenSource(resourceName));
    }

    private static Path resourceDirectory() throws URISyntaxException {
        return Path.of(java.util.Objects.requireNonNull(
                        ManifestAdapterTotalityTest.class.getResource(GOLDEN_ROOT))
                .toURI());
    }
}
