package sh.zolt.cli.dependency;

import sh.zolt.cli.CliTestRepository;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PlatformCommandTest extends PlatformCommandTestSupport {
    @TempDir
    private Path tempDir;

    @Test
    void platformAddRefreshesLockfileByDefault() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "enterprise-platform", "2026.1.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>enterprise-platform</artifactId>
                      <version>2026.1.0</version>
                      <dependencyManagement>
                        <dependencies>
                          <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>legacy-api</artifactId>
                            <version>1.5.0</version>
                          </dependency>
                        </dependencies>
                      </dependencyManagement>
                    </project>
                    """);
            Path projectDir = tempDir.resolve("demo");
            writeProjectConfig(projectDir, repository.baseUri().toString());

            CommandResult result = execute(
                    "platform",
                    "add",
                    "--cwd", projectDir.toString(),
                    "--cache-root", tempDir.resolve("cache").toString(),
                    "com.example:enterprise-platform:2026.1.0");

            assertEquals(0, result.exitCode());
            assertTrue(result.stdout().contains("Added platform com.example:enterprise-platform:2026.1.0 to [platforms]"));
            assertTrue(result.stdout().contains("Resolved 0 packages"));
            assertTrue(result.stdout().contains("1 downloaded"));
            assertTrue(Files.exists(projectDir.resolve("zolt.lock")));
        }
    }

    @Test
    void platformAddWritesPlatformWithoutResolveWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        CommandResult result = execute(
                "platform",
                "add",
                "--directory", projectDir.toString(),
                "--no-resolve",
                "com.example:enterprise-platform:2026.1.0");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains(
                "Added platform com.example:enterprise-platform:2026.1.0 to [platforms]"));
        assertTrue(result.stdout().contains("Skipped resolve"));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertTrue(config.contains("\"com.example:enterprise-platform\" = \"2026.1.0\""));
        assertFalse(Files.exists(projectDir.resolve("zolt.lock")));
    }

    @Test
    void platformAddHumanOutputSupportsForcedColorAndQuietMode() throws IOException {
        Path colorProjectDir = tempDir.resolve("color-demo");
        writeProjectConfig(colorProjectDir);
        Path quietProjectDir = tempDir.resolve("quiet-demo");
        writeProjectConfig(quietProjectDir);

        CommandResult color = execute(
                "--color=always",
                "platform",
                "add",
                "--directory", colorProjectDir.toString(),
                "--no-resolve",
                "com.example:enterprise-platform:2026.1.0");
        CommandResult quiet = execute(
                "--quiet",
                "platform",
                "add",
                "--directory", quietProjectDir.toString(),
                "--no-resolve",
                "com.example:enterprise-platform:2026.1.0");

        assertEquals(0, color.exitCode());
        assertTrue(color.stdout().contains("\u001B[32m✔\u001B[0m Added platform "
                + "com.example:enterprise-platform:2026.1.0 to [platforms]"));
        assertFalse(color.stdout().contains(
                "\u001B[32mAdded platform com.example:enterprise-platform:2026.1.0 to [platforms]\u001B[0m"));
        assertTrue(color.stdout().contains(
                "\u001B[32mSkipped\u001B[0m resolve; run zolt resolve to refresh zolt.lock."));
        assertFalse(color.stdout().contains(
                "\u001B[32mSkipped resolve; run zolt resolve to refresh zolt.lock.\u001B[0m"));
        assertEquals(0, quiet.exitCode());
        assertEquals("", quiet.stdout());
        String quietConfig = Files.readString(quietProjectDir.resolve("zolt.toml"));
        assertTrue(quietConfig.contains("\"com.example:enterprise-platform\" = \"2026.1.0\""));
        assertFalse(Files.exists(quietProjectDir.resolve("zolt.lock")));
    }

    @Test
    void platformAddWritesVersionRefPlatformWithoutResolveWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("demo") + """

                [repositories]
                "central" = "https://repo.maven.apache.org/maven2"

                [versions]
                enterprise = "2026.1.0"
                """);

        CommandResult result = execute(
                "--color=always",
                "platform",
                "add",
                "--cwd", projectDir.toString(),
                "--no-resolve",
                "--version-ref",
                "enterprise",
                "com.example:enterprise-platform");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains(
                "\u001B[32m✔\u001B[0m Added platform com.example:enterprise-platform with versionRef `enterprise` = 2026.1.0 to [platforms]"));
        assertFalse(result.stdout().contains(
                "\u001B[32mAdded platform com.example:enterprise-platform with versionRef `enterprise` = 2026.1.0 to [platforms]\u001B[0m"));
        assertTrue(result.stdout().contains(
                "\u001B[32mSkipped\u001B[0m resolve; run zolt resolve to refresh zolt.lock."));
        assertFalse(result.stdout().contains(
                "\u001B[32mSkipped resolve; run zolt resolve to refresh zolt.lock.\u001B[0m"));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertTrue(config.contains("[versions]\nenterprise = \"2026.1.0\""));
        assertTrue(config.contains("\"com.example:enterprise-platform\" = { versionRef = \"enterprise\" }"));
        assertFalse(Files.exists(projectDir.resolve("zolt.lock")));
    }

    @Test
    void platformAddRejectsUnknownVersionRefOrExplicitVersionWithVersionRef() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        CommandResult unknown = execute(
                "platform",
                "add",
                "--cwd", projectDir.toString(),
                "--version-ref",
                "enterprise",
                "com.example:enterprise-platform");
        CommandResult explicit = execute(
                "platform",
                "add",
                "--cwd", projectDir.toString(),
                "--version-ref",
                "enterprise",
                "com.example:enterprise-platform:2026.1.0");

        assertEquals(1, unknown.exitCode());
        assertTrue(unknown.stderr().contains("Unknown versionRef `enterprise`."));
        assertTrue(unknown.stderr().contains("Next: Add [versions].enterprise or use an explicit version."));
        assertEquals(1, explicit.exitCode());
        assertTrue(explicit.stderr().contains("Version-ref platform coordinate must not include a version."));
        assertTrue(explicit.stderr().contains(
                "Next: Use `--version-ref enterprise com.example:enterprise-platform`."));
    }

    @Test
    void platformAddRejectsUnsupportedLiteralVersion() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        CommandResult result = execute(
                "platform",
                "add",
                "--cwd", projectDir.toString(),
                "--no-resolve",
                "com.example:enterprise-platform:LATEST");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains(
                "Invalid platform version `LATEST` for platform. Dynamic versions are not supported"));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertFalse(config.contains("enterprise-platform"));
    }

}
