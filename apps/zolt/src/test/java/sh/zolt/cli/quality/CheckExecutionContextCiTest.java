package sh.zolt.cli.quality;

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

final class CheckExecutionContextCiTest {
    @TempDir
    private Path tempDir;

    @Test
    void checkContextCiRejectsLocalOverlayOrigins() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-local-overlay");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-ci-local-overlay"));
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:local-lib"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.example:local-lib"
                version = "1.0.0"
                source = "local-overlay:maven-local"
                scope = "compile"
                direct = true
                jar = "overlays/maven-local/com/example/local-lib/1.0.0/local-lib-1.0.0.jar"
                dependencies = []
                """);

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--check", "execution-context",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error execution-context com.example:local-lib:1.0.0 CI context rejects local repository overlay origin `local-overlay:maven-local`."));
        assertTrue(result.stdout().contains("next: Run `zolt resolve --locked --no-local-overlays`"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextCiRejectsMissingRepositoryCredentialEnvironment() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-missing-credentials");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-ci-missing-credentials") + """

                [repositories.company]
                url = "https://repo.example.test/maven"
                credentials = "company-artifactory"

                [credentials.company-artifactory]
                usernameEnv = "ZOLT_TEST_MISSING_CHECK_CONTEXT_USERNAME"
                passwordEnv = "ZOLT_TEST_MISSING_CHECK_CONTEXT_PASSWORD"
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--check", "execution-context",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error execution-context [repositoryCredentials.company-artifactory] CI context requires environment variables ZOLT_TEST_MISSING_CHECK_CONTEXT_USERNAME, ZOLT_TEST_MISSING_CHECK_CONTEXT_PASSWORD"));
        assertTrue(result.stdout().contains("repository `company` credentials `company-artifactory`"));
        assertTrue(result.stdout().contains("Secret values are never printed"));
        assertFalse(result.stdout().contains("repo.example.test/maven"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextCiRejectsMissingPublishCredentialEnvironment() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-missing-publish-credentials");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-ci-missing-publish-credentials") + """

                [publish]
                release = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                credentials = "publish-creds"

                [credentials.publish-creds]
                usernameEnv = "ZOLT_TEST_MISSING_PUBLISH_CHECK_USERNAME"
                passwordEnv = "ZOLT_TEST_MISSING_PUBLISH_CHECK_PASSWORD"
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--check", "execution-context",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error execution-context [repositoryCredentials.publish-creds] CI context requires environment variables ZOLT_TEST_MISSING_PUBLISH_CHECK_USERNAME, ZOLT_TEST_MISSING_PUBLISH_CHECK_PASSWORD"));
        assertTrue(result.stdout().contains("publish repository `company-releases` credentials `publish-creds`"));
        assertTrue(result.stdout().contains("Secret values are never printed"));
        assertFalse(result.stdout().contains("repo.example.test/releases"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextCiRejectsEmbeddedRepositoryCredentials() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-embedded-credentials");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-ci-embedded-credentials") + """

                [repositories.company]
                url = "https://user:super-secret-token@repo.example.test/maven"
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--check", "execution-context",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains(
                "error execution-context zolt.toml Invalid value for `repositories.company.url`"),
                result.stdout());
        assertTrue(result.stdout().contains("Repository URL declares embedded user information."), result.stdout());
        assertFalse(result.stdout().contains("user:super-secret-token"));
        assertFalse(result.stdout().contains("repo.example.test/maven"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextCiRejectsEmbeddedPublishCredentials() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-embedded-publish-credentials");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-ci-embedded-publish-credentials") + """

                [publish]
                release = "company-releases"

                [publish.repositories.company-releases]
                url = "https://publish-user:super-secret-token@repo.example.test/releases"
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--check", "execution-context",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains(
                "error execution-context zolt.toml Invalid value for `publish.repositories.company-releases.url`"),
                result.stdout());
        assertTrue(result.stdout().contains("Repository URL declares embedded user information."), result.stdout());
        assertFalse(result.stdout().contains("publish-user"));
        assertFalse(result.stdout().contains("super-secret-token"));
        assertFalse(result.stdout().contains("repo.example.test/releases"));
        assertEquals("", result.stderr());
    }

}
