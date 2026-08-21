package sh.zolt.cli.ide;

import sh.zolt.cli.CliTestRepository;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceIdeModelCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void ideModelWorkspaceReportsStaleRootLockfileByDefault() throws IOException {
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

                    [workspace.members]
                    include = ["apps/api"]

                    [repositories]
                    central = false

                    [repositories.test]
                    url = "%s"
                    """.formatted(repository.baseUri()));
            Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api") + """

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

                    [workspace.members]
                    include = ["apps/api"]

                    [repositories]
                    central = false

                    [repositories.test]
                    url = "%s"
                    """.formatted(repository.baseUri() + "./"));

            CommandResult result = execute(
                    "ide",
                    "model",
                    "--workspace",
                    "--cwd", apiDir.toString(),
                    "--cache-root", tempDir.resolve("cache").toString(),
                    "--format", "json");

            assertEquals(0, resolve.exitCode());
            assertEquals(0, result.exitCode());
            assertEquals("", result.stderr());
            assertTrue(result.stdout().contains("\"code\": \"LOCKFILE_STALE\""));
            assertTrue(result.stdout().contains("Workspace zolt.lock is out of date"));
            assertTrue(result.stdout().contains("\"nextStep\": \"Run zolt resolve --workspace.\""));
            assertEquals(existingLockfile, Files.readString(workspaceDir.resolve("zolt.lock")));
        }
    }

    @Test
    void workspaceIdeModelPrintsNestedJsonTimingsWhenRequested() throws IOException {
        WorkspaceApplicationFixture fixture = workspaceApplicationFixture("workspace-ide-timings");

        CommandResult result = execute(
                "ide", "model",
                "--workspace",
                "--format", "json",
                "--timings",
                "--timings-format", "json",
                "--cwd", fixture.workspaceDir().toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\"workspace\": {"));
        assertTrue(result.stderr().contains("\"phase\":\"discover ide workspace\""));
        assertTrue(result.stderr().contains("\"phase\":\"read workspace ide lock\""));
        assertTrue(result.stderr().contains("\"phase\":\"plan workspace ide classpaths\""));
        assertTrue(result.stderr().contains("\"phase\":\"export workspace ide projects\""));
        assertTrue(result.stderr().contains("\"phase\":\"export workspace ide edges\""));
        assertTrue(result.stderr().contains("\"phase\":\"assemble workspace ide model\""));
        assertTrue(result.stderr().contains("\"phase\":\"ide model export\""));
        assertTrue(result.stderr().contains("\"phase\":\"ide model json\""));
        assertTrue(result.stderr().contains("\"depth\":1"));
        assertTrue(result.stderr().contains("\"projects\":\"2\""));
    }

    @Test
    void ideModelWorkspacePrintsWorkspaceJson() throws IOException {
        Path workspaceDir = tempDir.resolve("workspace");
        Path apiDir = workspaceDir.resolve("apps/api");
        Path coreDir = workspaceDir.resolve("modules/core");
        Files.createDirectories(apiDir);
        Files.createDirectories(coreDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "workspace"

                [workspace.members]
                default = ["apps/api"]
                include = ["apps/api", "modules/core"]
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api"));
        Files.writeString(apiDir.resolve("zolt.lock"), "version = 1\n");
        Files.writeString(coreDir.resolve("zolt.toml"), memberConfig("core"));
        Files.writeString(coreDir.resolve("zolt.lock"), "version = 1\n");

        CommandResult result = execute(
                "ide",
                "model",
                "--workspace",
                "--cwd", apiDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--format", "json");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\"workspace\": {"));
        assertTrue(result.stdout().contains("\"name\": \"workspace\""));
        assertTrue(result.stdout().contains("\"members\": ["));
        assertTrue(result.stdout().contains("\"apps/api\""));
        assertTrue(result.stdout().contains("\"modules/core\""));
        assertTrue(result.stdout().contains("\"projects\": ["));
        assertTrue(result.stdout().contains("\"member\": \"apps/api\""));
        assertTrue(result.stdout().contains("\"member\": \"modules/core\""));
        assertTrue(result.stdout().contains("\"edges\": []"));
        assertTrue(result.stdout().contains("\"diagnostics\": []"));
    }

    private WorkspaceApplicationFixture workspaceApplicationFixture(String name) throws IOException {
        Path workspaceDir = tempDir.resolve(name);
        Path apiDir = workspaceDir.resolve("apps/api");
        Path coreDir = workspaceDir.resolve("modules/core");
        Files.createDirectories(apiDir);
        Files.createDirectories(coreDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "workspace"

                [workspace.members]
                include = ["apps/api", "modules/core"]
                """);
        Files.writeString(coreDir.resolve("zolt.toml"), memberConfig("core"));
        Path coreSource = coreDir.resolve("src/main/java/com/example/core/Core.java");
        Files.createDirectories(coreSource.getParent());
        Files.writeString(coreSource, """
                package com.example.core;

                public final class Core {
                    private Core() {
                    }

                    public static String message() {
                        return "core";
                    }
                }
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api") + """
                main = "com.example.api.Api"

                [dependencies]
                "com.example:core" = { workspace = true }
                """);
        Path apiSource = apiDir.resolve("src/main/java/com/example/api/Api.java");
        Files.createDirectories(apiSource.getParent());
        Files.writeString(apiSource, """
                package com.example.api;

                import com.example.core.Core;

                public final class Api {
                    private Api() {
                    }

                    public static void main(String[] args) {
                        System.out.println(Core.message() + ":" + args[0]);
                    }
                }
                """);
        return new WorkspaceApplicationFixture(workspaceDir, apiDir, coreDir);
    }

    private record WorkspaceApplicationFixture(Path workspaceDir, Path apiDir, Path coreDir) {
    }
}
