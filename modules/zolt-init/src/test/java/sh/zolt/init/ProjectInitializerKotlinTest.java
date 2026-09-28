package sh.zolt.init;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

final class ProjectInitializerKotlinTest {
    private final ProjectInitializer initializer = new ProjectInitializer();
    private final ManifestProjectConfigLoader loader = new ManifestProjectConfigLoader();

    @TempDir
    private Path tempDir;

    @Test
    void emitsCanonicalStandaloneKotlinProject() throws IOException {
        ProjectInitResult result = initializer.init(
                tempDir,
                "hello",
                "com.example",
                "21",
                true,
                ProjectInitLanguage.KOTLIN);

        assertEquals(
                """
                [project]
                name = "hello"
                version = "0.1.0"
                group = "com.example"
                java = 21
                main = "com.example.Main"

                [toolchain.kotlin]
                version = "2.2.0"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = "5.14.4"

                [build]
                sources = ["src/main/kotlin"]

                [test.sources]
                kotlin = ["src/test/kotlin"]
                """,
                Files.readString(result.configFile()));
        assertEquals(
                result.projectDirectory().resolve("src/main/kotlin/com/example/Main.kt"),
                result.mainSource());
        assertEquals(
                result.projectDirectory().resolve("src/test/kotlin/com/example/MainTest.kt"),
                result.testSource());
        assertTrue(Files.readString(result.mainSource()).contains("object Main"));
        assertTrue(Files.readString(result.mainSource()).contains("@JvmStatic"));
        assertTrue(Files.readString(result.testSource()).contains("Main.greeting()"));

        ProjectConfig config = loader.load(result.configFile());
        assertEquals(
                "2.2.0",
                config.dependencies().get("org.jetbrains.kotlin:kotlin-stdlib"));
        assertEquals(java.util.List.of("src/main/kotlin"), config.build().sourceRoots());
        assertEquals(
                java.util.List.of("src/test/kotlin"),
                config.build().testSourceRoots().kotlinSources());
    }

    @Test
    void workspaceOwnsKotlinToolchainAtRootAndLanguageInputsAtMember() throws IOException {
        ProjectInitResult result = initializer.initWorkspace(
                tempDir,
                "platform",
                "com.example",
                "21",
                true,
                false,
                ProjectInitLanguage.KOTLIN);
        Path member = result.projectDirectory().resolve("apps/platform");

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

                [toolchain.kotlin]
                version = "2.2.0"
                """,
                Files.readString(result.configFile()));
        assertEquals(
                """
                [project]
                name = "platform"
                main = "com.example.Main"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = "5.14.4"

                [build]
                sources = ["src/main/kotlin"]

                [test.sources]
                kotlin = ["src/test/kotlin"]
                """,
                Files.readString(member.resolve("zolt.toml")));
        assertEquals(
                member.resolve("src/main/kotlin/com/example/Main.kt"),
                result.mainSource());
        assertEquals(
                member.resolve("src/test/kotlin/com/example/MainTest.kt"),
                result.testSource());
    }

    @Test
    void noTestsRetainsKotlinRuntimeButOmitsTestConfiguration() throws IOException {
        ProjectInitResult result = initializer.init(
                tempDir,
                "hello",
                "com.example",
                "21",
                false,
                ProjectInitLanguage.KOTLIN);

        assertEquals(
                """
                [project]
                name = "hello"
                version = "0.1.0"
                group = "com.example"
                java = 21
                main = "com.example.Main"

                [toolchain.kotlin]
                version = "2.2.0"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"

                [build]
                sources = ["src/main/kotlin"]
                """,
                Files.readString(result.configFile()));
        assertFalse(Files.exists(result.testSource()));
    }

    @Test
    void workspaceWithoutTestsKeepsOnlyKotlinRuntimeAndMainRoot() throws IOException {
        ProjectInitResult result = initializer.initWorkspace(
                tempDir,
                "platform",
                "com.example",
                "21",
                false,
                false,
                ProjectInitLanguage.KOTLIN);
        Path member = result.projectDirectory().resolve("apps/platform");

        assertEquals(
                """
                [project]
                name = "platform"
                main = "com.example.Main"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"

                [build]
                sources = ["src/main/kotlin"]
                """,
                Files.readString(member.resolve("zolt.toml")));
        assertFalse(Files.exists(member.resolve("src/test")));
        assertFalse(Files.exists(result.testSource()));
    }

    @Test
    void escapesKotlinKeywordsInPackageNames() throws IOException {
        ProjectInitResult result = initializer.init(
                tempDir,
                "hello",
                "when.is",
                "21",
                true,
                ProjectInitLanguage.KOTLIN);

        assertTrue(Files.readString(result.mainSource()).contains("package `when`.`is`"));
        assertTrue(Files.readString(result.testSource()).contains("package `when`.`is`"));
    }
}
