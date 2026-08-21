package sh.zolt.cli.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestSupport.CommandResult;

final class CheckWorkspaceExecutionContextInheritanceCommandTest {
    private static final String MISSING_TOKEN =
            "ZOLT_TEST_MISSING_WORKSPACE_ROOT_REPOSITORY_TOKEN";
    private static final String MISSING_PUBLISH_TOKEN =
            "ZOLT_TEST_MISSING_WORKSPACE_ROOT_PUBLISH_TOKEN";

    @TempDir
    private Path tempDir;

    @Test
    void explicitExecutionContextReportsMissingRootRepositorySecretWithoutLock()
            throws IOException {
        Path workspace = repositoryWorkspace(MISSING_TOKEN);

        CommandResult result = check(workspace);

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains(
                "error execution-context apps/api [repositoryCredentials.company] "
                        + "CI context requires environment variable "
                        + MISSING_TOKEN
                        + " for repository `company` credentials `company`"));
        assertTrue(result.stdout().contains(
                "error execution-context ci CI context requires zolt.lock"));
        assertEquals("", result.stderr());
    }

    @Test
    void rootRepositorySecretPassesForEveryInheritingMember()
            throws IOException {
        Path workspace = repositoryWorkspace("PATH");
        writeLock(workspace);

        CommandResult result = check(workspace);

        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(result.stdout().contains(
                "ok execution-context apps/api repository-credentials "
                        + "CI credential preflight passed for 1 credentialed repository."));
        assertTrue(result.stdout().contains(
                "ok execution-context modules/core repository-credentials "
                        + "CI credential preflight passed for 1 credentialed repository."));
        assertEquals("", result.stderr());
    }

    @Test
    void memberPublishRepositoryUsesPresentRootCredential()
            throws IOException {
        Path workspace = publishWorkspace("PATH");
        writeLock(workspace);

        CommandResult result = check(workspace);

        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(result.stdout().contains(
                "ok execution-context apps/api publish-credentials "
                        + "CI publish credential preflight passed for 1 credentialed publish repository."));
        assertEquals("", result.stderr());
    }

    @Test
    void memberPublishRepositoryReportsMissingRootCredentialAsQualityFailure()
            throws IOException {
        Path workspace = publishWorkspace(MISSING_PUBLISH_TOKEN);
        writeLock(workspace);

        CommandResult result = check(workspace);

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains(
                "error execution-context apps/api [repositoryCredentials.company] "
                        + "CI context requires environment variable "
                        + MISSING_PUBLISH_TOKEN
                        + " for publish repository `releases` credentials `company`"));
        assertEquals("", result.stderr());
    }

    private Path repositoryWorkspace(String tokenEnvironment)
            throws IOException {
        Path workspace = tempDir.resolve("repository-" + tokenEnvironment);
        writeMembers(workspace, false);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "credential-workspace"

                [workspace.members]
                include = ["apps/api", "modules/core"]

                [repositories]
                central = false

                [repositories.company]
                url = "https://repo.example.test/maven"
                credentials = "company"

                [credentials.company]
                tokenEnv = "%s"
                """.formatted(tokenEnvironment));
        return workspace;
    }

    private Path publishWorkspace(String tokenEnvironment)
            throws IOException {
        Path workspace = tempDir.resolve("publish-" + tokenEnvironment);
        writeMembers(workspace, true);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "publish-credential-workspace"

                [workspace.members]
                include = ["apps/api"]

                [credentials.company]
                tokenEnv = "%s"
                """.formatted(tokenEnvironment));
        return workspace;
    }

    private static void writeMembers(Path workspace, boolean publish)
            throws IOException {
        Path api = workspace.resolve("apps/api");
        Files.createDirectories(api);
        Files.writeString(
                api.resolve("zolt.toml"),
                memberConfig("api")
                        + (publish
                                ? """

                                [publish]
                                release = "releases"

                                [publish.repositories.releases]
                                url = "https://repo.example.test/releases"
                                credentials = "company"
                                """
                                : ""));
        if (!publish) {
            Path core = workspace.resolve("modules/core");
            Files.createDirectories(core);
            Files.writeString(core.resolve("zolt.toml"), memberConfig("core"));
        }
    }

    private static void writeLock(Path workspace) throws IOException {
        Files.writeString(workspace.resolve("zolt.lock"), "version = 7\n");
    }

    private static CommandResult check(Path workspace) {
        return execute(
                "check",
                "--workspace",
                "--context", "ci",
                "--check", "execution-context",
                "--cwd", workspace.toString());
    }
}
