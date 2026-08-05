package sh.zolt.cli.workspace;

import sh.zolt.cli.WorkspaceCommandFixture;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.WorkspaceCommandFixture.WorkspaceApplicationFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceBuildCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void buildWorkspaceCompilesMembersInDependencyOrder() throws IOException {
        WorkspaceApplicationFixture fixture = WorkspaceCommandFixture.create(tempDir, "workspace");

        CommandResult result = execute(
                "build",
                "--workspace",
                "--all",
                "--cwd", fixture.apiDir().toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("Resolved workspace dependencies because zolt.lock was missing"));
        assertTrue(result.stdout().contains("Compiled 1 main source files in modules/core"));
        assertTrue(result.stdout().contains("Compiled 1 main source files in apps/api"));
        assertTrue(result.stdout().contains("Compiled 2 workspace main source files"));
        assertTrue(Files.exists(fixture.workspaceDir().resolve("zolt.toml")));
        assertFalse(Files.exists(fixture.workspaceDir().resolve("zolt-workspace.toml")));
        assertTrue(Files.exists(fixture.coreDir().resolve("target/classes/com/example/core/Core.class")));
        assertTrue(Files.exists(fixture.apiDir().resolve("target/classes/com/example/api/Api.class")));
    }

    @Test
    void buildWorkspacePrintsNestedJsonTimingsWhenRequested() throws IOException {
        WorkspaceApplicationFixture fixture = WorkspaceCommandFixture.create(tempDir, "workspace");

        CommandResult result = execute(
                "build",
                "--workspace",
                "--all",
                "--timings",
                "--timings-format", "json",
                "--cwd", fixture.apiDir().toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Compiled 2 workspace main source files"));
        String expectedMaxWorkers = "\"workspaceBuildMaxWorkers\":\""
                + Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors()))
                + "\"";
        String[] lines = result.stderr().lines().toArray(String[]::new);
        assertEquals(4, lines.length);
        assertTrue(lines[0].contains("\"phase\":\"workspace lock freshness\""));
        assertTrue(lines[0].contains("\"workspaceLockFreshness\""));
        assertTrue(lines[1].contains("\"phase\":\"plan workspace build\""));
        assertTrue(lines[1].contains("\"depth\":1"));
        assertTrue(lines[1].contains("\"includedMembers\":\"2\""));
        assertTrue(lines[1].contains("\"selectedMembers\":\"2\""));
        assertTrue(lines[1].contains("\"resolvedLockfile\":\"true\""));
        assertTrue(lines[1].contains("\"workspaceDiscoveryNanos\""));
        assertTrue(lines[1].contains("\"workspaceSelectionNanos\""));
        assertTrue(lines[1].contains("\"workspaceResolutionNanos\""));
        assertTrue(lines[1].contains("\"workspaceLockfileReadNanos\""));
        assertTrue(lines[1].contains("\"workspaceEdges\":\"1\""));
        assertTrue(lines[1].contains("\"workspaceLockfilePackages\""));
        assertTrue(lines[2].contains("\"phase\":\"compile workspace members\""));
        assertTrue(lines[2].contains("\"depth\":1"));
        assertTrue(lines[2].contains("\"members\":\"2\""));
        assertTrue(lines[2].contains("\"sourceFiles\":\"2\""));
        assertTrue(lines[2].contains("\"workspaceBuildWaves\":\"2\""));
        assertTrue(lines[2].contains("\"workspaceSchedulerIdleNanos\":"));
        assertTrue(lines[2].contains("\"workspaceReadyQueuePeak\":\"1\""));
        assertTrue(lines[2].contains(expectedMaxWorkers));
        assertTrue(lines[2].contains("\"mainCompilationsSkipped\":\"0\""));
        assertTrue(lines[2].contains("\"mainCompilationsExecuted\":\"2\""));
        assertTrue(lines[2].contains("\"mainSourcesRecompiled\""));
        assertTrue(lines[2].contains("\"mainAbiChangedClasses\""));
        assertTrue(lines[2].contains("\"workspaceAbiInvalidations\""));
        assertTrue(lines[2].contains("\"workspaceGraphConstructionNanos\""));
        assertTrue(lines[2].contains("\"workspaceClasspathCalculationNanos\""));
        assertTrue(lines[2].contains("\"workspacePackageCalculationNanos\":\"0\""));
        assertTrue(lines[2].contains("\"workspaceMemberExecutionNanos\""));
        assertTrue(lines[2].contains("\"workspaceFileSnapshotNanos\""));
        assertTrue(lines[2].contains("\"workspaceBytesHashed\""));
        assertTrue(lines[2].contains("\"workspaceFilesHashed\""));
        assertTrue(lines[2].contains("\"workspaceMembersConsidered\":\"2\""));
        assertTrue(lines[2].contains("\"workspaceMembersDeclaredClean\":\"0\""));
        assertTrue(lines[2].contains("\"workspaceMemberPipelineInvocations\":\"2\""));
        assertTrue(lines[2].contains("\"workspaceAbiStateReads\""));
        assertTrue(lines[2].contains("\"workspaceAbiStateCacheHits\""));
        assertTrue(lines[2].contains("\"workspaceToolchainResolutions\""));
        assertTrue(lines[2].contains("\"workspaceToolchainCacheHits\""));
        assertTrue(lines[2].contains("\"workspaceToolchainLockfileParses\":\"1\""));
        assertTrue(lines[2].contains("\"workspaceToolchainIdentityCalculations\""));
        assertTrue(lines[2].contains("\"workspaceToolchainIdentityCacheHits\""));
        assertTrue(lines[2].contains("\"workspaceClasspathCalculations\":\"2\""));
        assertTrue(lines[2].contains("\"workspacePackageCalculations\":\"0\""));
        assertTrue(lines[3].contains("\"phase\":\"build workspace\""));
        assertTrue(lines[3].contains("\"depth\":0"));
        assertTrue(lines[3].contains("\"members\":\"2\""));
        assertTrue(lines[3].contains("\"sourceFiles\":\"2\""));
        assertTrue(lines[3].contains("\"workspaceBuildWaves\":\"2\""));
        assertTrue(lines[3].contains(expectedMaxWorkers));
        assertTrue(lines[3].contains("\"mainCompilationsSkipped\":\"0\""));
        assertTrue(lines[3].contains("\"mainCompilationsExecuted\":\"2\""));
        assertTrue(lines[3].contains("\"mainSourcesRecompiled\""));
        assertTrue(lines[3].contains("\"workspaceAbiInvalidations\""));
    }

    @Test
    void buildWorkspaceReportsSkippedMembersOnNoOpBuild() throws IOException {
        WorkspaceApplicationFixture fixture = WorkspaceCommandFixture.create(tempDir, "workspace");
        Path cacheRoot = tempDir.resolve("cache");
        CommandResult first = execute(
                "build",
                "--workspace",
                "--all",
                "--cwd", fixture.apiDir().toString(),
                "--cache-root", cacheRoot.toString());

        CommandResult second = execute(
                "build",
                "--workspace",
                "--all",
                "--cwd", fixture.apiDir().toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, first.exitCode());
        assertEquals(0, second.exitCode());
        assertTrue(second.stdout().contains("Skipped main compilation in modules/core; inputs are unchanged"));
        assertTrue(second.stdout().contains("Skipped main compilation in apps/api; inputs are unchanged"));
        assertTrue(second.stdout().contains("Skipped workspace main compilation; inputs are unchanged"));
        assertFalse(second.stdout().contains("Compiled 1 main source files in modules/core"));
        assertFalse(second.stdout().contains("Compiled 1 main source files in apps/api"));
        assertFalse(second.stdout().contains("Compiled 2 workspace main source files"));
    }

    @Test
    void buildWorkspaceRecompilesConsumersWhenDependencyConstantChanges() throws IOException {
        WorkspaceApplicationFixture fixture = WorkspaceCommandFixture.create(tempDir, "workspace");
        Path coreSource = fixture.coreDir().resolve("src/main/java/com/example/core/Core.java");
        Path apiSource = fixture.apiDir().resolve("src/main/java/com/example/api/Api.java");
        Files.writeString(coreSource, """
                package com.example.core;

                public final class Core {
                    public static final String MESSAGE = "one";

                    private Core() {
                    }
                }
                """);
        Files.writeString(apiSource, """
                package com.example.api;

                import com.example.core.Core;

                public final class Api {
                    public static String message() {
                        return Core.MESSAGE;
                    }
                }
                """);
        Path cacheRoot = tempDir.resolve("cache");
        CommandResult first = execute(
                "build",
                "--workspace",
                "--all",
                "--cwd", fixture.apiDir().toString(),
                "--cache-root", cacheRoot.toString());
        Path apiClass = fixture.apiDir().resolve("target/classes/com/example/api/Api.class");
        byte[] firstApiClass = Files.readAllBytes(apiClass);
        Files.writeString(coreSource, Files.readString(coreSource).replace("\"one\"", "\"two\""));

        CommandResult second = execute(
                "build",
                "--workspace",
                "--all",
                "--cwd", fixture.apiDir().toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, first.exitCode());
        assertEquals(0, second.exitCode());
        assertTrue(second.stdout().contains("Compiled 1 main source files in modules/core"));
        assertTrue(second.stdout().contains("Compiled 1 main source files in apps/api"));
        assertFalse(second.stdout().contains("Skipped main compilation in apps/api; inputs are unchanged"));
        assertFalse(Arrays.equals(firstApiClass, Files.readAllBytes(apiClass)));
    }

    @Test
    void buildWorkspaceMemberSelectionCompilesDependenciesOnly() throws IOException {
        WorkspaceApplicationFixture fixture = WorkspaceCommandFixture.create(tempDir, "workspace");
        Path workerDir = addMember(fixture.workspaceDir(), "apps/worker", "worker");

        CommandResult result = execute(
                "build",
                "--workspace",
                "--member", "apps/api",
                "--cwd", fixture.apiDir().toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("Compiled 1 main source files in modules/core"));
        assertTrue(result.stdout().contains("Compiled 1 main source files in apps/api"));
        assertFalse(result.stdout().contains("apps/worker"));
        assertTrue(Files.exists(fixture.coreDir().resolve("target/classes/com/example/core/Core.class")));
        assertTrue(Files.exists(fixture.apiDir().resolve("target/classes/com/example/api/Api.class")));
        assertFalse(Files.exists(workerDir.resolve("target/classes/com/example/worker/Worker.class")));
    }

    @Test
    void buildWorkspaceMembersOptionSelectsCommaSeparatedMembers() throws IOException {
        WorkspaceApplicationFixture fixture = WorkspaceCommandFixture.create(tempDir, "workspace");
        Path workerDir = addMember(fixture.workspaceDir(), "apps/worker", "worker");
        Path adminDir = addMember(fixture.workspaceDir(), "apps/admin", "admin");
        Files.writeString(fixture.workspaceDir().resolve("zolt.toml"), """
                [workspace]
                name = "workspace"
                members = ["apps/api", "modules/core", "apps/worker", "apps/admin"]
                """);

        CommandResult result = execute(
                "build",
                "--workspace",
                "--members", "apps/api,apps/worker",
                "--cwd", fixture.apiDir().toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("Compiled 1 main source files in modules/core"));
        assertTrue(result.stdout().contains("Compiled 1 main source files in apps/api"));
        assertTrue(result.stdout().contains("Compiled 1 main source files in apps/worker"));
        assertFalse(result.stdout().contains("apps/admin"));
        assertTrue(Files.exists(fixture.coreDir().resolve("target/classes/com/example/core/Core.class")));
        assertTrue(Files.exists(fixture.apiDir().resolve("target/classes/com/example/api/Api.class")));
        assertTrue(Files.exists(workerDir.resolve("target/classes/com/example/worker/Worker.class")));
        assertFalse(Files.exists(adminDir.resolve("target/classes/com/example/admin/Admin.class")));
    }

    @Test
    void workspaceMembersOptionConflictsWithAll() throws IOException {
        Path workspaceDir = tempDir.resolve("workspace");
        Path apiDir = workspaceDir.resolve("apps/api");
        Files.createDirectories(apiDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "workspace"
                members = ["apps/api"]
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api"));

        CommandResult result = execute(
                "build",
                "--workspace",
                "--all",
                "--members", "apps/api",
                "--cwd", apiDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Use either --all or member selection for workspace selection, not both."));
    }

    private static Path addMember(Path workspaceDir, String memberPath, String name) throws IOException {
        Path memberDir = workspaceDir.resolve(memberPath);
        Files.createDirectories(memberDir);
        Files.writeString(memberDir.resolve("zolt.toml"), memberConfig(name));
        Path source = memberDir.resolve("src/main/java/com/example/" + name + "/" + capitalized(name) + ".java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example.%s;

                public final class %s {
                }
                """.formatted(name, capitalized(name)));
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "workspace"
                members = ["apps/api", "modules/core", "%s"]
                """.formatted(memberPath));
        return memberDir;
    }

    private static String capitalized(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
