package sh.zolt.project.init;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.toml.ZoltTomlWriter;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.toml.WorkspaceConfigParser;
import sh.zolt.workspace.toml.WorkspaceTomlWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProjectInitializerTest {
    private final ZoltTomlParser parser = new ZoltTomlParser();
    private final WorkspaceConfigParser workspaceParser = new WorkspaceConfigParser();
    private final WorkspaceTomlWriter workspaceWriter = new WorkspaceTomlWriter();
    private final ProjectInitializer initializer =
            new ProjectInitializer(new ZoltTomlWriter()::write, this::writeWorkspaceConfig);

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
    void generatedConfigParsesCleanly() {
        ProjectInitResult result = initializer.init(tempDir, "hello", "com.example", "21");

        ProjectConfig config = parser.parse(result.configFile());

        assertEquals("hello", config.project().name());
        assertEquals("com.example", config.project().group());
        assertEquals("21", config.project().java());
        assertEquals("com.example.Main", config.project().main().orElseThrow());
        assertEquals(
                java.util.Map.of("org.junit.jupiter:junit-jupiter", "5.14.4"),
                config.testDependencies());
    }

    @Test
    void createsWorkspaceRootConfigAndDefaultAppMember() {
        ProjectInitResult result = initializer.initWorkspace(tempDir, "platform", "com.example", "21");

        Path workspaceRoot = tempDir.resolve("platform");
        Path memberRoot = workspaceRoot.resolve("apps/platform");
        assertEquals(workspaceRoot, result.projectDirectory());
        assertTrue(Files.exists(workspaceRoot.resolve("zolt.toml")));
        assertTrue(Files.exists(memberRoot.resolve("zolt.toml")));
        assertTrue(Files.exists(result.mainSource()));
        assertTrue(Files.exists(result.testSource()));

        WorkspaceConfig workspaceConfig = workspaceParser.parseRootConfig(result.configFile());
        ProjectConfig memberConfig = parser.parse(memberRoot.resolve("zolt.toml"));
        assertEquals("platform", workspaceConfig.name());
        assertEquals(java.util.List.of("apps/platform"), workspaceConfig.members());
        assertEquals(java.util.List.of("apps/platform"), workspaceConfig.defaultMembers());
        assertEquals("platform", memberConfig.project().name());
        assertEquals("com.example.Main", memberConfig.project().main().orElseThrow());
        assertEquals(
                java.util.Map.of("org.junit.jupiter:junit-jupiter", "5.14.4"),
                memberConfig.testDependencies());
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

        ProjectConfig config = parser.parse(result.configFile());

        assertTrue(config.testDependencies().isEmpty());
        assertFalse(Files.exists(result.testSource()));
    }

    @Test
    void canCreateWorkspaceWithoutTests() {
        ProjectInitResult result = initializer.initWorkspace(
                tempDir, "platform", "com.example", "21", false);
        Path memberRoot = result.projectDirectory().resolve("apps/platform");

        ProjectConfig config = parser.parse(memberRoot.resolve("zolt.toml"));

        assertTrue(config.testDependencies().isEmpty());
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
    void refusesNonEmptyDirectory() throws IOException {
        Path existing = tempDir.resolve("hello");
        Files.createDirectories(existing);
        Files.writeString(existing.resolve("note.txt"), "already here");

        ProjectInitException exception = assertThrows(
                ProjectInitException.class,
                () -> initializer.init(tempDir, "hello", "com.example", "21"));

        assertTrue(exception.getMessage().contains("is not empty"));
    }

    private void writeWorkspaceConfig(Path path, WorkspaceInitConfig config) {
        workspaceWriter.write(
                path,
                new WorkspaceConfig(
                        config.name(),
                        config.members(),
                        config.defaultMembers(),
                        config.repositories(),
                        config.platforms()));
    }
}
