package sh.zolt.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.sha256;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

final class WorkspaceRepositoryAuthenticationCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolveLockedResolveDryRunAndPublishPreserveMemberAuthentication() throws IOException {
        String token = System.getenv("PATH");
        assumeTrue(token != null && !token.isBlank(), "PATH is required as a non-secret test token");
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.requireBearerToken(token);
            repository.addArtifact(
                    "org.example",
                    "dependency",
                    "1.0.0",
                    """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>org.example</groupId>
                      <artifactId>dependency</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path workspace = writeWorkspace(repository);

            CommandResult resolve = execute(
                    "resolve",
                    "--workspace",
                    "--cwd", workspace.toString(),
                    "--cache-root", tempDir.resolve("resolve-cache").toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            assertAuthenticated(repository.authorizations(), token);

            repository.clearAuthorizations();
            CommandResult locked = execute(
                    "resolve",
                    "--workspace",
                    "--locked",
                    "--cwd", workspace.toString(),
                    "--cache-root", tempDir.resolve("locked-cache").toString());
            assertEquals(0, locked.exitCode(), locked.stderr());
            assertAuthenticated(repository.authorizations(), token);

            repository.clearAuthorizations();
            CommandResult dryRun = execute(
                    "publish",
                    "--workspace",
                    "--dry-run",
                    "--cwd", workspace.toString(),
                    "--cache-root", tempDir.resolve("resolve-cache").toString());
            assertEquals(0, dryRun.exitCode(), () -> dryRun.stdout() + dryRun.stderr());
            assertTrue(dryRun.stdout().contains("Nothing uploaded (dry run)."), dryRun.stdout());

            CommandResult publish = execute(
                    "publish",
                    "--workspace",
                    "--cwd", workspace.toString(),
                    "--cache-root", tempDir.resolve("resolve-cache").toString());
            assertEquals(0, publish.exitCode(), () -> publish.stdout() + publish.stderr());
            assertTrue(publish.stdout().contains("Uploaded the family."), publish.stdout());
            assertAuthenticated(repository.authorizations(), token);
        }
    }

    @Test
    void rootRepositoryAuthenticationIsInheritedWithoutMemberRepetition()
            throws IOException {
        String token = System.getenv("PATH");
        assumeTrue(
                token != null && !token.isBlank(),
                "PATH is required as a non-secret test token");
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.requireBearerToken(token);
            repository.addArtifact(
                    "org.example",
                    "dependency",
                    "1.0.0",
                    """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>org.example</groupId>
                      <artifactId>dependency</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path workspace = writeRootAuthenticatedWorkspace(repository);

            CommandResult resolve = execute(
                    "resolve",
                    "--workspace",
                    "--cwd", workspace.toString(),
                    "--cache-root", tempDir.resolve("root-auth-cache").toString());

            assertEquals(0, resolve.exitCode(), resolve.stderr());
            assertAuthenticated(repository.authorizations(), token);
        }
    }

    private Path writeWorkspace(CliTestRepository repository) throws IOException {
        Path workspace = tempDir.resolve("authenticated-workspace");
        Path member = workspace.resolve("lib");
        Files.createDirectories(member.resolve("target"));
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "authenticated-workspace"
                members = ["lib"]

                [repositories]
                internal = "%s"
                """.formatted(repository.baseUri()));
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "lib"
                version = "1.0.0"
                group = "com.acme"
                java = "21"

                [repositories]
                internal = { url = "%s", credentials = "company" }

                [repositoryCredentials.company]
                tokenEnv = "PATH"

                [dependencies]
                "org.example:dependency" = "1.0.0"

                [publish]
                releaseRepository = "internal"

                [publish.repositories.internal]
                url = "%s"
                credentials = "company"
                """.formatted(repository.baseUri(), repository.baseUri()));
        Path artifact = member.resolve("target/lib-1.0.0.jar");
        Files.writeString(artifact, "authenticated command fixture\n");
        Files.writeString(
                member.resolve("target/lib-1.0.0.jar.zolt-package.json"),
                """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/lib-1.0.0.jar",
                  "archiveSha256": "%s"
                }
                """.formatted(sha256(artifact)));
        return workspace;
    }

    private Path writeRootAuthenticatedWorkspace(
            CliTestRepository repository) throws IOException {
        Path workspace = tempDir.resolve("root-authenticated-workspace");
        Path member = workspace.resolve("lib");
        Files.createDirectories(member);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "root-authenticated-workspace"
                members = ["lib"]

                [repositories]
                internal = { url = "%s", credentials = "company" }

                [repositoryCredentials.company]
                tokenEnv = "PATH"
                """.formatted(repository.baseUri()));
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "lib"
                version = "1.0.0"
                group = "com.acme"
                java = "21"

                [dependencies]
                "org.example:dependency" = "1.0.0"
                """);
        return workspace;
    }

    private static void assertAuthenticated(Map<String, String> authorizations, String token) {
        assertFalse(authorizations.isEmpty());
        assertTrue(authorizations.values().stream().allMatch(("Bearer " + token)::equals), authorizations.toString());
    }
}
