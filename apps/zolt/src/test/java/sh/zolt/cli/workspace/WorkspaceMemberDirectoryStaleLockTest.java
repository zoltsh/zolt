package sh.zolt.cli.workspace;

import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceMemberDirectoryStaleLockTest {
    @TempDir
    private Path tempDir;

    @Test
    void buildMemberDirectoryRedirectsToWorkspaceMemberInsteadOfDeadEndResolve() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "app", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>app</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            repository.addArtifact("com.example", "extra", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>extra</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path workspaceDir = tempDir.resolve("workspace");
            Path memberDir = workspaceDir.resolve("modules/core");
            Files.createDirectories(memberDir);
            Files.writeString(workspaceDir.resolve("zolt.toml"), """
                    [workspace]
                    name = "workspace"

                    [workspace.members]
                    include = ["modules/core"]

                    [repositories]
                    central = false

                    [repositories.test]
                    url = "%s"
                    """.formatted(repository.baseUri()));
            writeMemberConfig(memberDir, repository.baseUri().toString(), "com.example:app");
            Path memberSource = memberDir.resolve("src/main/java/com/example/core/Core.java");
            Files.createDirectories(memberSource.getParent());
            Files.writeString(memberSource, """
                    package com.example.core;

                    public final class Core {
                    }
                    """);
            Path cacheRoot = tempDir.resolve("cache");

            CommandResult workspaceResolve = execute(
                    "resolve",
                    "--workspace",
                    "--cwd", memberDir.toString(),
                    "--cache-root", cacheRoot.toString());
            // Standalone resolve on the member writes modules/core/zolt.lock; a --workspace resolve never does.
            CommandResult memberResolve = execute(
                    "resolve",
                    "--cwd", memberDir.toString(),
                    "--cache-root", cacheRoot.toString());
            // Change a member input so the member-local zolt.lock is now stale.
            writeMemberConfig(memberDir, repository.baseUri().toString(), "com.example:extra");

            CommandResult result = execute(
                    "build",
                    "--cwd", memberDir.toString(),
                    "--cache-root", cacheRoot.toString());

            assertEquals(0, workspaceResolve.exitCode());
            assertEquals(0, memberResolve.exitCode());
            assertEquals(1, result.exitCode());
            assertTrue(Files.exists(memberDir.resolve("zolt.lock")));
            // No dead-end bare `zolt resolve` next-step.
            assertFalse(
                    result.stderr().contains("Run `zolt resolve` to refresh it"),
                    "expected no dead-end zolt resolve next-step, got: " + result.stderr());
            // Instead the message names the workspace-member path that actually works.
            assertTrue(
                    result.stderr().contains("--workspace --member modules/core"),
                    "expected redirect naming --workspace --member, got: " + result.stderr());
            assertFalse(Files.exists(memberDir.resolve("target/classes/com/example/core/Core.class")));
        }
    }

    @Test
    void buildStandaloneProjectKeepsSingleProjectStaleLockMessage() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "app", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>app</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            repository.addArtifact("com.example", "extra", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>extra</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path projectDir = tempDir.resolve("standalone");
            Files.createDirectories(projectDir);
            writeMemberConfig(projectDir, repository.baseUri().toString(), "com.example:app");
            Path mainSource = projectDir.resolve("src/main/java/com/example/core/Core.java");
            Files.createDirectories(mainSource.getParent());
            Files.writeString(mainSource, """
                    package com.example.core;

                    public final class Core {
                    }
                    """);
            Path cacheRoot = tempDir.resolve("cache");

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cacheRoot.toString());
            writeMemberConfig(projectDir, repository.baseUri().toString(), "com.example:extra");

            CommandResult result = execute(
                    "build",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cacheRoot.toString());

            assertEquals(0, resolve.exitCode());
            assertEquals(1, result.exitCode());
            assertTrue(result.stderr().contains("zolt.lock is out of date"));
            assertTrue(
                    result.stderr().contains("Run `zolt resolve` to refresh it"),
                    "expected standalone single-project message, got: " + result.stderr());
            assertFalse(result.stderr().contains("--workspace --member"));
            assertFalse(Files.exists(projectDir.resolve("target/classes/com/example/core/Core.class")));
        }
    }

    private static void writeMemberConfig(Path memberDir, String repositoryUrl, String dependency)
            throws IOException {
        Files.writeString(memberDir.resolve("zolt.toml"), CliTestSupport.memberConfig("core") + """

                [repositories]
                central = false

                [repositories.test]
                url = "%s"

                [dependencies]
                "%s" = "1.0.0"
                """.formatted(repositoryUrl, dependency));
    }
}
