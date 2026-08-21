package sh.zolt.project.init;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.ZoltManifestParser;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/**
 * {@code zolt init} emits the final manifest language and nothing the language already defaults
 * (design §5.1, §22.2). Every assertion here is on the emitted bytes.
 */
final class ProjectInitializerTest {
    private final ManifestProjectConfigLoader loader = new ManifestProjectConfigLoader();
    private final ZoltManifestParser parser = new ZoltManifestParser();
    private final ProjectInitializer initializer = new ProjectInitializer();

    @TempDir
    private Path tempDir;

    @Test
    void createsProjectFiles() {
        ProjectInitResult result = initializer.init(tempDir, "hello", "com.example", "21");

        assertTrue(Files.exists(result.configFile()));
        assertTrue(Files.exists(result.mainSource()));
        assertTrue(Files.exists(result.testSource()));
        assertTrue(Files.exists(result.projectDirectory().resolve(".gitignore")));
    }

    @Test
    void emitsTheSparseCanonicalStandaloneManifest() throws IOException {
        ProjectInitResult result = initializer.init(tempDir, "hello", "com.example", "21");

        assertEquals(
                """
                [project]
                name = "hello"
                version = "0.1.0"
                group = "com.example"
                java = 21
                main = "com.example.Main"

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = "5.14.4"
                """,
                Files.readString(result.configFile()));
    }

    @Test
    void generatedProjectLoadsThroughTheFinalBoundary() {
        ProjectInitResult result = initializer.init(tempDir, "hello", "com.example", "21");

        ProjectConfig config = loader.load(result.configFile());

        assertEquals("hello", config.project().name());
        assertEquals("com.example", config.project().group());
        assertEquals("21", config.project().java());
        assertEquals("com.example.Main", config.project().main().orElseThrow());
        assertEquals(
                java.util.Map.of("org.junit.jupiter:junit-jupiter", "5.14.4"),
                config.testDependencies());
    }

    @Test
    void workspaceInitEmitsAMembersTableWithAnExplicitDefault() throws IOException {
        ProjectInitResult result = initializer.initWorkspace(tempDir, "platform", "com.example", "21");

        assertEquals(
                """
                [workspace]
                name = "platform"

                [workspace.members]
                default = ["apps/platform"]
                include = ["apps/platform"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21
                """,
                Files.readString(result.configFile()));
    }

    @Test
    void workspaceMemberDoesNotMaterializeInheritedIdentity() throws IOException {
        ProjectInitResult result = initializer.initWorkspace(tempDir, "platform", "com.example", "21");
        Path memberRoot = result.projectDirectory().resolve("apps/platform");

        assertEquals(
                """
                [project]
                name = "platform"
                main = "com.example.Main"

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = "5.14.4"
                """,
                Files.readString(memberRoot.resolve("zolt.toml")));
    }

    @Test
    void workspaceInitAllMembersOmitsDefault() throws IOException {
        ProjectInitResult result = initializer.initWorkspace(
                tempDir, "platform", "com.example", "21", true, true);

        assertEquals(
                """
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/platform"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21
                """,
                Files.readString(result.configFile()));
    }

    @Test
    void generatedWorkspaceManifestsParseAsFinalManifests() throws IOException {
        ProjectInitResult result = initializer.initWorkspace(tempDir, "platform", "com.example", "21");
        Path memberRoot = result.projectDirectory().resolve("apps/platform");

        AuthoredManifest root = parser.parse(Files.readString(result.configFile())).authored();
        AuthoredManifest member = parser.parse(Files.readString(memberRoot.resolve("zolt.toml"))).authored();

        assertEquals("platform", root.workspace().orElseThrow().name().value());
        assertTrue(root.project().isEmpty(), "the workspace root is virtual");
        assertEquals(Optional.empty(), member.project().orElseThrow().identity().group());
        assertEquals(Optional.empty(), member.project().orElseThrow().identity().version());
    }

    @Test
    void generatedSourcesUseRequestedPackage() throws IOException {
        ProjectInitResult result = initializer.init(tempDir, "hello", "dev.zolt.demo", "21");

        assertEquals(
                result.projectDirectory().resolve("src/main/java/dev/zolt/demo/Main.java"),
                result.mainSource());
        assertTrue(Files.readString(result.mainSource()).contains("package dev.zolt.demo;"));
        String testSource = Files.readString(result.testSource());
        assertTrue(testSource.contains("final class MainTest"));
        assertTrue(testSource.contains("@Test"));
        assertTrue(testSource.contains("assertEquals(\"Hello from hello!\", Main.greeting())"));
    }

    @Test
    void canCreateProjectWithoutTests() {
        ProjectInitResult result = initializer.init(tempDir, "hello", "com.example", "21", false);

        ProjectConfig config = loader.load(result.configFile());

        assertTrue(config.testDependencies().isEmpty());
        assertFalse(Files.exists(result.testSource()));
    }

    @Test
    void canCreateWorkspaceWithoutTests() throws IOException {
        ProjectInitResult result = initializer.initWorkspace(
                tempDir, "platform", "com.example", "21", false);
        Path memberRoot = result.projectDirectory().resolve("apps/platform");

        assertEquals(
                """
                [project]
                name = "platform"
                main = "com.example.Main"
                """,
                Files.readString(memberRoot.resolve("zolt.toml")));
        assertFalse(Files.exists(memberRoot.resolve("src/test")));
        assertFalse(Files.exists(result.testSource()));
    }

    @Test
    void rejectsPathLikeProjectName() {
        ProjectInitException exception = assertThrows(
                ProjectInitException.class,
                () -> initializer.init(tempDir, "nested/hello", "com.example", "21"));

        assertEquals("Project name must be a directory name, not a path.", exception.getMessage());
    }

    @Test
    void rejectsInvalidGroup() {
        ProjectInitException exception = assertThrows(
                ProjectInitException.class,
                () -> initializer.init(tempDir, "hello", "com.123bad", "21"));

        assertEquals("Project group must be a valid Java package, for example `com.example`.", exception.getMessage());
    }

    @Test
    void rejectsALegacyJavaVersionSpelling() {
        ProjectInitException exception = assertThrows(
                ProjectInitException.class,
                () -> initializer.init(tempDir, "hello", "com.example", "1.8"));

        assertEquals(
                "Java version must be a feature release number such as 21, not `1.8`.",
                exception.getMessage());
    }

    @Test
    void refusesNonEmptyDirectory() throws IOException {
        Path existing = tempDir.resolve("hello");
        Files.createDirectories(existing);
        Files.writeString(existing.resolve("note.txt"), "already here");

        ProjectInitException exception = assertThrows(
                ProjectInitException.class,
                () -> initializer.init(tempDir, "hello", "com.example", "21"));

        assertTrue(exception.getMessage().contains("is not empty"));
    }
}
