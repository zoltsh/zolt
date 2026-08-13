package sh.zolt.cli.command.build;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.toolchain.ManagedJavaToolchainTestFixture;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.lock.ToolchainLockfileService;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildCommandToolchainTest {
    @TempDir
    private Path tempDir;

    @Test
    void buildCompilesWithManagedJavaToolchain() throws IOException {
        Path projectDir = ManagedJavaToolchainTestFixture.writeProject(tempDir, "managed-build-demo");
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = ManagedJavaToolchainTestFixture.locked();
        new ToolchainLockfileService().writeJava(projectDir.resolve("zolt.lock"), locked);
        Path javacMarker = projectDir.resolve("target/javac-marker.txt");
        ManagedJavaToolchainTestFixture.installManagedToolchain(store, locked, javacMarker);

        var result = execute(
                "build",
                "--directory", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root", tempDir.resolve("toolchains").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/Main.class")));
        assertTrue(Files.readString(javacMarker).contains("javac=" + store.javac(locked)));
        assertTrue(result.stdout().contains("Compiled 1 main source files"));
    }

    @Test
    void workspaceBuildUsesMemberToolchainConfigAndWorkspaceLock() throws IOException {
        Path workspaceDir = tempDir.resolve("managed-workspace");
        Path memberDir = workspaceDir.resolve("apps/api");
        LockedJavaToolchain locked = ManagedJavaToolchainTestFixture.locked();
        Files.createDirectories(memberDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "managed-workspace"
                members = ["apps/api"]
                defaultMembers = ["apps/api"]
                """);
        Files.writeString(memberDir.resolve("zolt.toml"), """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = "%s"
                main = "com.example.Main"

                [toolchain.java]
                version = "%s"
                distribution = "temurin"
                features = []
                policy = "require-managed"
                """.formatted(locked.request().version(), locked.request().version()));
        Path source = memberDir.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.println("workspace");
                    }
                }
                """);
        new ToolchainLockfileService().writeJava(workspaceDir.resolve("zolt.lock"), locked);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        Path javacMarker = workspaceDir.resolve("javac-marker.txt");
        ManagedJavaToolchainTestFixture.installManagedToolchain(store, locked, javacMarker);

        var result = execute(
                "build",
                "--workspace",
                "--directory", workspaceDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root", tempDir.resolve("toolchains").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(Files.notExists(memberDir.resolve("zolt.lock")));
        assertTrue(Files.exists(memberDir.resolve("target/classes/com/example/Main.class")));
        assertTrue(Files.readString(javacMarker).contains("javac=" + store.javac(locked)));
        assertTrue(result.stdout().contains("Compiled 1 main source files in apps/api"));
    }

    @Test
    void workspaceBuildUsesRootToolchainConfigWhenMemberDoesNotDeclareOne() throws IOException {
        Path workspaceDir = tempDir.resolve("root-managed-workspace");
        Path memberDir = workspaceDir.resolve("apps/api");
        LockedJavaToolchain locked = ManagedJavaToolchainTestFixture.locked();
        Files.createDirectories(memberDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "root-managed-workspace"
                members = ["apps/api"]
                defaultMembers = ["apps/api"]

                [toolchain.java]
                version = "%s"
                distribution = "temurin"
                features = []
                policy = "require-managed"
                """.formatted(locked.request().version()));
        Files.writeString(memberDir.resolve("zolt.toml"), """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = "%s"
                main = "com.example.Main"
                """.formatted(locked.request().version()));
        Path source = memberDir.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.println("workspace");
                    }
                }
                """);
        new ToolchainLockfileService().writeJava(workspaceDir.resolve("zolt.lock"), locked);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        Path javacMarker = workspaceDir.resolve("javac-marker.txt");
        ManagedJavaToolchainTestFixture.installManagedToolchain(store, locked, javacMarker);

        var result = execute(
                "build",
                "--workspace",
                "--directory", workspaceDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root", tempDir.resolve("toolchains").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(Files.exists(memberDir.resolve("target/classes/com/example/Main.class")));
        assertTrue(Files.readString(javacMarker).contains("javac=" + store.javac(locked)));
        assertTrue(result.stdout().contains("Compiled 1 main source files in apps/api"));
    }

    @Test
    void workspaceCompileIdentityUsesOnlyTheMatchingToolchainLockRecord()
            throws IOException {
        Path workspaceDir = tempDir.resolve("toolchain-identity-workspace");
        Path memberDir = workspaceDir.resolve("apps/api");
        LockedJavaToolchain locked = ManagedJavaToolchainTestFixture.locked();
        Files.createDirectories(memberDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "toolchain-identity-workspace"
                members = ["apps/api"]
                defaultMembers = ["apps/api"]
                """);
        Files.writeString(memberDir.resolve("zolt.toml"), """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = "%s"

                [toolchain.java]
                version = "%s"
                distribution = "temurin"
                features = []
                policy = "require-managed"
                """.formatted(locked.request().version(), locked.request().version()));
        Path source =
                memberDir.resolve("src/main/java/com/example/Api.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;

                public final class Api {
                }
                """);
        ToolchainLockfileService lockfiles = new ToolchainLockfileService();
        lockfiles.writeJava(workspaceDir.resolve("zolt.lock"), locked);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        Path javacMarker = workspaceDir.resolve("javac-marker.txt");
        ManagedJavaToolchainTestFixture.installManagedToolchain(
                store,
                locked,
                javacMarker);

        CommandResult cold = buildWorkspace(workspaceDir);
        assertEquals(0, cold.exitCode(), cold.stderr());
        assertPipelineInvocations(cold, 1);
        int coldCompiles = Files.readAllLines(javacMarker).size();
        CommandResult warm = buildWorkspace(workspaceDir);
        assertEquals(0, warm.exitCode(), warm.stderr());
        assertPipelineInvocations(warm, 0);
        assertEquals(coldCompiles, Files.readAllLines(javacMarker).size());

        LockedJavaToolchain unrelated = new LockedJavaToolchain(
                "unrelated-windows-toolchain",
                locked.request(),
                HostPlatform.parse("windows-x64"),
                locked.resolvedVersion(),
                locked.resolvedDistribution(),
                "test:unrelated",
                locked.artifactUri(),
                locked.artifactSha256(),
                locked.layout());
        lockfiles.writeJava(
                workspaceDir.resolve("zolt.lock"),
                java.util.List.of(locked, unrelated));
        CommandResult unrelatedChange = buildWorkspace(workspaceDir);
        assertEquals(
                0,
                unrelatedChange.exitCode(),
                unrelatedChange.stderr());
        assertPipelineInvocations(unrelatedChange, 0);
        assertEquals(coldCompiles, Files.readAllLines(javacMarker).size());

        LockedJavaToolchain changedMatch = new LockedJavaToolchain(
                locked.id(),
                locked.request(),
                locked.platform(),
                locked.resolvedVersion(),
                locked.resolvedDistribution(),
                locked.catalog(),
                "https://example.invalid/jdk.tar.gz",
                "a".repeat(64),
                locked.layout());
        lockfiles.writeJava(
                workspaceDir.resolve("zolt.lock"),
                java.util.List.of(changedMatch, unrelated));
        CommandResult matchingChange = buildWorkspace(workspaceDir);

        assertEquals(0, matchingChange.exitCode(), matchingChange.stderr());
        assertPipelineInvocations(matchingChange, 1);
    }

    @Test
    void buildFailsClearlyWhenStrictManagedToolchainIsMissing() throws IOException {
        Path projectDir = ManagedJavaToolchainTestFixture.writeProject(tempDir, "missing-build-demo");

        var result = execute(
                "build",
                "--directory", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root", tempDir.resolve("missing-toolchains").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Java toolchain is not ready for build"));
        assertTrue(result.stderr().contains("zolt toolchain status"));
        assertTrue(result.stderr().contains("zolt toolchain sync"));
        assertTrue(Files.notExists(projectDir.resolve("target/classes/com/example/Main.class")));
    }

    private CommandResult buildWorkspace(Path workspaceDir) {
        return execute(
                "build",
                "--workspace",
                "--directory", workspaceDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--timings",
                "--timings-format", "json",
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root", tempDir.resolve("toolchains").toString());
    }

    private static void assertPipelineInvocations(
            CommandResult result,
            int expected) {
        assertTrue(
                result.stderr().contains(
                        "\"workspaceMemberPipelineInvocations\":\""
                                + expected
                                + "\""),
                result.stderr());
    }
}
