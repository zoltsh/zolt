package sh.zolt.cli.workspace;

import sh.zolt.cli.CliTestSupport;


import sh.zolt.cli.CliTestRepository;

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

final class WorkspaceBuildCommandStaleLockTest {
    @TempDir
    private Path tempDir;

    @Test
    void buildWorkspaceRejectsStaleGeneratedRootLockfileBeforeCompiling() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "app", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>app</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path workspaceDir = tempDir.resolve("workspace");
            Path apiDir = workspaceDir.resolve("apps/api");
            Files.createDirectories(apiDir);
            Files.writeString(workspaceDir.resolve("zolt.toml"), """
                    [workspace]
                    name = "workspace"
                    members = ["apps/api"]

                    [repositories]
                    test = "%s"
                    """.formatted(repository.baseUri()));
            Files.writeString(apiDir.resolve("zolt.toml"), CliTestSupport.memberConfig("api") + """

                    [dependencies]
                    "com.example:app" = "1.0.0"
                    """);
            CommandResult resolve = execute(
                    "resolve",
                    "--workspace",
                    "--cwd", apiDir.toString(),
                    "--cache-root", tempDir.resolve("cache").toString());
            String existingLockfile = Files.readString(workspaceDir.resolve("zolt.lock"));
            Files.writeString(workspaceDir.resolve("zolt.toml"), """
                    [workspace]
                    name = "workspace"
                    members = ["apps/api"]

                    [repositories]
                    test = "%s"
                    """.formatted(repository.baseUri().toString().replace("127.0.0.1", "localhost")));
            Path apiSource = apiDir.resolve("src/main/java/com/example/api/Api.java");
            Files.createDirectories(apiSource.getParent());
            Files.writeString(apiSource, """
                    package com.example.api;

                    public final class Api {
                    }
                    """);

            CommandResult result = execute(
                    "build",
                    "--workspace",
                    "--cwd", apiDir.toString(),
                    "--cache-root", tempDir.resolve("cache").toString());

            assertEquals(0, resolve.exitCode());
            assertEquals(1, result.exitCode());
            assertTrue(existingLockfile.contains("projectResolutionFingerprint = \"sha256:"));
            assertTrue(
                    result.stderr().contains("Workspace zolt.lock is out of date"),
                    result.stderr());
            assertEquals(existingLockfile, Files.readString(workspaceDir.resolve("zolt.lock")));
            assertFalse(Files.exists(apiDir.resolve("target/classes/com/example/api/Api.class")));
        }
    }
}
