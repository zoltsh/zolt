package sh.zolt.cli.toolchain;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toolchain.lock.JavaToolchainLayout;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.lock.ToolchainLockfileService;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ExecCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void execRunsCommandInsideManagedJavaToolchain() throws IOException {
        Path project = writeProject("managed-exec", ToolchainPolicy.PREFER_MANAGED);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = locked(ToolchainPolicy.PREFER_MANAGED);
        new ToolchainLockfileService().writeJava(project.resolve("zolt.lock"), locked);
        installManagedToolchain(store, locked, 0);

        var result = execute(
                "exec",
                "--directory", project.toString(),
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root", tempDir.resolve("toolchains").toString(),
                "--",
                "java",
                "hello");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("javaHome=" + store.javaHome(locked)));
        assertTrue(result.stdout().contains("pathHead=" + store.javaHome(locked).resolve("bin")));
        assertTrue(result.stdout().contains("cwd=" + project.toRealPath()));
        assertTrue(result.stdout().contains("args=hello"));
    }

    @Test
    void execReturnsChildExitCodeAndStderr() throws IOException {
        Path project = writeProject("managed-exit", ToolchainPolicy.PREFER_MANAGED);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = locked(ToolchainPolicy.PREFER_MANAGED);
        new ToolchainLockfileService().writeJava(project.resolve("zolt.lock"), locked);
        installManagedToolchain(store, locked, 7);

        var result = execute(
                "exec",
                "--directory", project.toString(),
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root", tempDir.resolve("toolchains").toString(),
                "--",
                "java");

        assertEquals(7, result.exitCode());
        assertTrue(result.stderr().contains("child stderr"));
    }

    @Test
    void execUsesGlobalJavaDefaultOutsideProject() throws IOException {
        Path workDir = tempDir.resolve("not-a-project");
        Path configPath = tempDir.resolve("home/config.toml");
        Files.createDirectories(workDir);
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, """
                version = 1

                [defaults.toolchain.java]
                version = "21"
                distribution = "temurin"
                features = []
                policy = "prefer-managed"
                """);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = locked(ToolchainPolicy.PREFER_MANAGED);
        new ToolchainLockfileService().writeJava(configPath.getParent().resolve("global-toolchains.lock"), locked);
        installManagedToolchain(store, locked, 0);

        var result = execute(
                "exec",
                "--directory", workDir.toString(),
                "--global-config", configPath.toString(),
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root", tempDir.resolve("toolchains").toString(),
                "--",
                "java",
                "hello");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("javaHome=" + store.javaHome(locked)));
        assertTrue(result.stdout().contains("cwd=" + workDir.toRealPath()));
        assertTrue(result.stdout().contains("args=hello"));
    }

    @Test
    void execCanUseWorkspaceRootToolchainTable() throws IOException {
        Path workspace = writeWorkspace("workspace-exec", ToolchainPolicy.PREFER_MANAGED);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = locked(ToolchainPolicy.PREFER_MANAGED);
        new ToolchainLockfileService().writeJava(workspace.resolve("zolt.lock"), locked);
        installManagedToolchain(store, locked, 0);

        var result = execute(
                "exec",
                "--directory", workspace.toString(),
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root", tempDir.resolve("toolchains").toString(),
                "--",
                "java",
                "hello");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("javaHome=" + store.javaHome(locked)));
        assertTrue(result.stdout().contains("cwd=" + workspace.toRealPath()));
        assertTrue(result.stdout().contains("args=hello"));
    }

    @Test
    void execResolvesNativeImageFromLockedToolchainMetadata() throws IOException {
        Path project = writeNativeProject("managed-native-exec", ToolchainPolicy.PREFER_MANAGED);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = nativeImageLocked(ToolchainPolicy.PREFER_MANAGED);
        new ToolchainLockfileService().writeJava(project.resolve("zolt.lock"), locked);
        installManagedNativeImageToolchain(store, locked);

        var result = execute(
                "exec",
                "--directory", project.toString(),
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root", tempDir.resolve("toolchains").toString(),
                "--",
                "native-image",
                "--version");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("managedNativeImage=" + store.nativeImage(locked).orElseThrow()));
        assertTrue(result.stdout().contains("args=--version"));
    }

    @Test
    void execFailsClearlyWhenStrictManagedToolchainIsMissing() throws IOException {
        Path project = writeProject("strict-missing", ToolchainPolicy.REQUIRE_MANAGED);

        var result = execute(
                "exec",
                "--directory", project.toString(),
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root", tempDir.resolve("toolchains").toString(),
                "--",
                "java",
                "-version");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Java toolchain is not ready for exec"));
        assertTrue(result.stderr().contains("zolt toolchain status"));
        assertTrue(result.stderr().contains("zolt toolchain sync"));
    }

    private Path writeProject(String name, ToolchainPolicy policy) throws IOException {
        Path project = tempDir.resolve(name);
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [toolchain.java]
                version = 21
                distribution = "temurin"
                features = []
                policy = "%s"
                """.formatted(name, policy.id()));
        Files.writeString(project.resolve("zolt.lock"), "version = 7\n\n");
        return project;
    }

    private Path writeWorkspace(String name, ToolchainPolicy policy) throws IOException {
        Path workspace = tempDir.resolve(name);
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "%s"

                [workspace.members]
                include = ["apps/demo"]

                [toolchain.java]
                version = 21
                distribution = "temurin"
                features = []
                policy = "%s"
                """.formatted(name, policy.id()));
        Files.writeString(workspace.resolve("zolt.lock"), "version = 7\n\n");
        return workspace;
    }

    private Path writeNativeProject(String name, ToolchainPolicy policy) throws IOException {
        Path project = tempDir.resolve(name);
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [toolchain.java]
                version = 21
                distribution = "graalvm-community"
                features = ["native-image"]
                policy = "%s"
                """.formatted(name, policy.id()));
        Files.writeString(project.resolve("zolt.lock"), "version = 7\n\n");
        return project;
    }

    private static LockedJavaToolchain locked(ToolchainPolicy policy) {
        JavaToolchainRequest request = new JavaToolchainRequest(
                "21",
                JavaDistribution.TEMURIN,
                Set.<JavaFeature>of(),
                policy);
        return new LockedJavaToolchain(
                "java-temurin-21",
                request,
                HostPlatform.parse("linux-x64"),
                "21",
                JavaDistribution.TEMURIN,
                "builtin:java-temurin-21",
                "https://example.test/jdk.tar.gz",
                "0".repeat(64),
                JavaToolchainLayout.standard(false));
    }

    private static LockedJavaToolchain nativeImageLocked(ToolchainPolicy policy) {
        JavaToolchainRequest request = new JavaToolchainRequest(
                "21",
                JavaDistribution.GRAALVM_COMMUNITY,
                Set.of(JavaFeature.NATIVE_IMAGE),
                policy);
        return new LockedJavaToolchain(
                "java-graalvm-community-21-native-image",
                request,
                HostPlatform.parse("linux-x64"),
                "21",
                JavaDistribution.GRAALVM_COMMUNITY,
                "builtin:java-graalvm-community-21-native-image",
                "https://example.test/jdk.tar.gz",
                "0".repeat(64),
                new JavaToolchainLayout(
                        ".",
                        "bin/java",
                        "bin/javac",
                        "bin/jar",
                        "lib/svm/bin/native-image"));
    }

    private static void installManagedToolchain(
            ToolchainStore store,
            LockedJavaToolchain locked,
            int exitCode) throws IOException {
        javaTool(store.java(locked), exitCode);
        tool(store.javac(locked));
        tool(store.jar(locked));
    }

    private static void installManagedNativeImageToolchain(
            ToolchainStore store,
            LockedJavaToolchain locked) throws IOException {
        tool(store.java(locked));
        tool(store.javac(locked));
        tool(store.jar(locked));
        nativeImageTool(store.nativeImage(locked).orElseThrow());
    }

    private static void javaTool(Path path, int exitCode) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                #!/usr/bin/env bash
                set -euo pipefail

                printf 'javaHome=%%s\\n' "$JAVA_HOME"
                printf 'pathHead=%%s\\n' "${PATH%%%%:*}"
                printf 'cwd=%%s\\n' "$(pwd)"
                printf 'args=%%s\\n' "$*"
                printf 'child stderr\\n' >&2
                exit %d
                """.formatted(exitCode));
        path.toFile().setExecutable(true);
    }

    private static void tool(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "");
        path.toFile().setExecutable(true);
    }

    private static void nativeImageTool(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                #!/usr/bin/env bash
                set -euo pipefail

                printf 'managedNativeImage=%s\\n' "$0"
                printf 'args=%s\\n' "$*"
                """);
        path.toFile().setExecutable(true);
    }
}
