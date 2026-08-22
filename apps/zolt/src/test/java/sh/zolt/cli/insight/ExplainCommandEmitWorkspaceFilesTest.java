package sh.zolt.cli.insight;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** : `zolt explain --emit-toml` can write workspace drafts as files. */
final class ExplainCommandEmitWorkspaceFilesTest {
    @TempDir
    private Path tempDir;

    @Test
    void mavenReactorWritesWorkspaceFilesAndResolves() throws IOException {
        writeMavenReactor();

        CommandResult explain = execute(
                "explain",
                "--emit-toml",
                "--emit-toml-output", ".",
                "--cwd", tempDir.toString(),
                "--source", "maven");

        assertEquals(0, explain.exitCode(), () -> explain.stderr());
        assertEquals("", explain.stderr());
        assertWritten(explain, "zolt.toml");
        assertWritten(explain, "core/zolt.toml");
        assertWritten(explain, "app/zolt.toml");
        assertTrue(Files.readString(tempDir.resolve("zolt.toml")).contains("[workspace]"));
        assertTrue(Files.readString(tempDir.resolve("app/zolt.toml"))
                .contains("\"com.acme:core\" = { workspace = true }"));
        assertFalse(Files.readString(tempDir.resolve("zolt.toml")).contains("# ---"));
        assertFalse(Files.readString(tempDir.resolve("app/zolt.toml")).contains("# ---"));

        CommandResult resolve = execute(
                "resolve",
                "--workspace",
                "--cwd", tempDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());
        assertEquals(0, resolve.exitCode(), () -> resolve.stdout() + resolve.stderr());
    }

    @Test
    void gradleMultiProjectWritesWorkspaceFilesAndResolves() throws IOException {
        writeGradleMultiProject();

        CommandResult explain = execute(
                "explain",
                "--emit-toml",
                "--emit-toml-output", ".",
                "--cwd", tempDir.toString(),
                "--source", "gradle");

        assertEquals(0, explain.exitCode(), () -> explain.stderr());
        assertEquals("", explain.stderr());
        assertWritten(explain, "zolt.toml");
        assertWritten(explain, "app/zolt.toml");
        assertWritten(explain, "core/zolt.toml");
        assertTrue(Files.readString(tempDir.resolve("app/zolt.toml"))
                .contains("\"com.example:core\" = { workspace = true }"));

        CommandResult resolve = execute(
                "resolve",
                "--workspace",
                "--cwd", tempDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());
        assertEquals(0, resolve.exitCode(), () -> resolve.stdout() + resolve.stderr());
    }

    @Test
    void fileWritingModeRefusesExistingTomlWithoutOverwrite() throws IOException {
        writeMavenReactor();
        CommandResult first = execute(
                "explain",
                "--emit-toml",
                "--emit-toml-output", ".",
                "--cwd", tempDir.toString(),
                "--source", "maven");
        assertEquals(0, first.exitCode(), () -> first.stderr());

        CommandResult blocked = execute(
                "explain",
                "--emit-toml",
                "--emit-toml-output", ".",
                "--cwd", tempDir.toString(),
                "--source", "maven");

        assertNotEquals(0, blocked.exitCode());
        assertEquals("", blocked.stdout());
        assertTrue(blocked.stderr().contains(tempDir.resolve("zolt.toml").toAbsolutePath().normalize().toString()),
                () -> blocked.stderr());
        assertTrue(blocked.stderr().contains("--emit-toml-overwrite"), () -> blocked.stderr());

        Files.writeString(tempDir.resolve("app/zolt.toml"), "old\n");
        CommandResult overwritten = execute(
                "explain",
                "--emit-toml",
                "--emit-toml-output", ".",
                "--emit-toml-overwrite",
                "--cwd", tempDir.toString(),
                "--source", "maven");

        assertEquals(0, overwritten.exitCode(), () -> overwritten.stderr());
        assertTrue(Files.readString(tempDir.resolve("app/zolt.toml")).contains("[project]"));
        assertFalse(Files.readString(tempDir.resolve("app/zolt.toml")).contains("old"));
    }

    @Test
    void fileWritingModeDoesNotChangeDefaultWorkspaceStdoutBundle() throws IOException {
        writeGradleMultiProject();

        CommandResult before = execute("explain", "--emit-toml", "--cwd", tempDir.toString(), "--source", "gradle");
        CommandResult files = execute(
                "explain",
                "--emit-toml",
                "--emit-toml-output", "draft",
                "--cwd", tempDir.toString(),
                "--source", "gradle");
        CommandResult after = execute("explain", "--emit-toml", "--cwd", tempDir.toString(), "--source", "gradle");

        assertEquals(0, before.exitCode(), () -> before.stderr());
        assertEquals(0, files.exitCode(), () -> files.stderr());
        assertEquals(0, after.exitCode(), () -> after.stderr());
        assertEquals(before.stdout(), after.stdout());
        assertTrue(before.stdout().contains("# --- app/zolt.toml ---"), () -> before.stdout());
        assertTrue(Files.isRegularFile(tempDir.resolve("draft/app/zolt.toml")));
        assertFalse(Files.isRegularFile(tempDir.resolve("zolt.toml")));
    }

    @Test
    void fileWritingModeDoesNotChangeDefaultSingleModuleStdout() throws IOException {
        writeSingleMavenProject();

        CommandResult before = execute("explain", "--emit-toml", "--cwd", tempDir.toString(), "--source", "maven");
        CommandResult files = execute(
                "explain",
                "--emit-toml",
                "--emit-toml-output", "draft",
                "--cwd", tempDir.toString(),
                "--source", "maven");
        CommandResult after = execute("explain", "--emit-toml", "--cwd", tempDir.toString(), "--source", "maven");

        assertEquals(0, before.exitCode(), () -> before.stderr());
        assertEquals(0, files.exitCode(), () -> files.stderr());
        assertEquals(0, after.exitCode(), () -> after.stderr());
        assertEquals(before.stdout(), after.stdout());
        assertTrue(before.stdout().startsWith("# Draft zolt.toml generated by `zolt explain --emit-toml`"));
        assertFalse(before.stdout().contains("# ---"));
        assertTrue(Files.isRegularFile(tempDir.resolve("draft/zolt.toml")));
        assertFalse(Files.isRegularFile(tempDir.resolve("zolt.toml")));
    }

    @Test
    void emitTomlOutputRequiresEmitToml() throws IOException {
        writeMavenReactor();

        CommandResult result = execute(
                "explain",
                "--emit-toml-output", ".",
                "--cwd", tempDir.toString(),
                "--source", "maven");

        assertNotEquals(0, result.exitCode());
        assertTrue(result.stderr().contains("--emit-toml-output"), () -> result.stderr());
        assertTrue(result.stderr().contains("--emit-toml"), () -> result.stderr());
    }

    private void writeMavenReactor() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>reactor</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                  <modules>
                    <module>core</module>
                    <module>app</module>
                  </modules>
                </project>
                """);
        writeMavenModule("core", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.acme</groupId>
                    <artifactId>reactor</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>core</artifactId>
                </project>
                """);
        writeMavenModule("app", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.acme</groupId>
                    <artifactId>reactor</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>app</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>com.acme</groupId>
                      <artifactId>core</artifactId>
                      <version>${project.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
    }

    private void writeMavenModule(String name, String pom) throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), pom);
    }

    private void writeSingleMavenProject() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>single</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                </project>
                """);
    }

    private void writeGradleMultiProject() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), """
                rootProject.name = 'sales'
                include 'app', 'core'
                """);
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }\n");
        writeGradleModule("app", """
                plugins { id 'java-library' }
                sourceCompatibility = JavaVersion.VERSION_21
                dependencies {
                    implementation project(':core')
                }
                """);
        writeGradleModule("core", """
                plugins { id 'java-library' }
                sourceCompatibility = JavaVersion.VERSION_21
                """);
    }

    private void writeGradleModule(String name, String buildGradle) throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("build.gradle"), buildGradle);
    }

    private void assertWritten(CommandResult result, String relativePath) {
        Path path = tempDir.resolve(relativePath).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(path), () -> path + " was not written");
        assertTrue(result.stdout().contains(path.toString()), () -> result.stdout());
    }
}
