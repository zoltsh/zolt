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

final class ToolchainCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void syncWritesJavaToolchainLockMetadata() throws IOException {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [toolchain.java]
                version = 21
                distribution = "graalvm-community"
                features = ["native-image"]
                """);
        Files.writeString(project.resolve("zolt.lock"), "version = 7\n\n");
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        install(store, locked());

        var result = execute(
                "toolchain",
                "sync",
                "--directory",
                project.toString(),
                "--target",
                "linux-x64",
                "--install-root",
                tempDir.resolve("toolchains").toString());

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("Synced Java toolchain"));
        assertTrue(result.stdout().contains("java-graalvm-community-21-native-image for linux-x64"));
        assertTrue(result.stdout().contains("Managed Java toolchain is already installed"));
        String lock = Files.readString(project.resolve("zolt.lock"));
        assertTrue(lock.contains("[[toolchain.java]]"));
        assertEquals(4, countJavaLocks(lock));
        assertTrue(lock.contains("request.distribution = \"graalvm-community\""));
        assertTrue(lock.contains("platform.os = \"linux\""));
        assertTrue(lock.contains("platform.os = \"macos\""));
    }

    @Test
    void statusReportsInstalledManagedToolchain() throws IOException {
        Path project = tempDir.resolve("status-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [toolchain.java]
                version = 21
                distribution = "graalvm-community"
                features = ["native-image"]
                """);
        Files.writeString(project.resolve("zolt.lock"), "version = 7\n\n");
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = locked();
        new ToolchainLockfileService().writeJava(project.resolve("zolt.lock"), locked);
        install(store, locked);

        var result = execute(
                "toolchain",
                "status",
                "--directory",
                project.toString(),
                "--target",
                "linux-x64",
                "--install-root",
                tempDir.resolve("toolchains").toString());

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("source: managed"));
        assertTrue(result.stdout().contains("javaHome: " + store.javaHome(locked)));
        assertTrue(result.stdout().contains("native-image: " + store.nativeImage(locked).orElseThrow()));
    }

    @Test
    void statusCanReportJson() throws IOException {
        Path project = tempDir.resolve("json-status-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [toolchain.java]
                version = 21
                distribution = "graalvm-community"
                features = ["native-image"]
                """);
        Files.writeString(project.resolve("zolt.lock"), "version = 7\n\n");
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = locked();
        new ToolchainLockfileService().writeJava(project.resolve("zolt.lock"), locked);
        install(store, locked);

        var result = execute(
                "--color=always",
                "toolchain",
                "status",
                "--json",
                "--directory",
                project.toString(),
                "--target",
                "linux-x64",
                "--install-root",
                tempDir.resolve("toolchains").toString());

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\"schemaVersion\": 1"));
        assertTrue(result.stdout().contains("\"ok\": true"));
        assertTrue(result.stdout().contains("\"source\": \"[toolchain.java]\""));
        assertTrue(result.stdout().contains("\"source\": \"managed\""));
        assertTrue(result.stdout().contains("\"nativeImage\": \"" + store.nativeImage(locked).orElseThrow()));
        assertTrue(result.stdout().contains("\"features\": [\"native-image\"]"));
        assertTrue(!result.stdout().contains("\u001B["));
    }

    @Test
    void syncCanUseWorkspaceRootToolchainTable() throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "demo"

                [workspace.members]
                include = ["apps/demo"]

                [toolchain.java]
                version = 21
                distribution = "graalvm-community"
                features = ["native-image"]
                """);
        Files.writeString(workspace.resolve("zolt.lock"), "version = 7\n\n");
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        install(store, locked());

        var result = execute(
                "toolchain",
                "sync",
                "--directory",
                workspace.toString(),
                "--target",
                "linux-x64",
                "--install-root",
                tempDir.resolve("toolchains").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Synced Java toolchain"));
        String lock = Files.readString(workspace.resolve("zolt.lock"));
        assertTrue(lock.contains("[[toolchain.java]]"));
        assertEquals(4, countJavaLocks(lock));
        assertTrue(lock.contains("request.features = [\"native-image\"]"));
    }

    @Test
    void statusCanUseWorkspaceRootToolchainTable() throws IOException {
        Path workspace = tempDir.resolve("workspace-status");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "demo"

                [workspace.members]
                include = ["apps/demo"]

                [toolchain.java]
                version = 21
                distribution = "graalvm-community"
                features = ["native-image"]
                """);
        Files.writeString(workspace.resolve("zolt.lock"), "version = 7\n\n");
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = locked();
        new ToolchainLockfileService().writeJava(workspace.resolve("zolt.lock"), locked);
        install(store, locked);

        var result = execute(
                "toolchain",
                "status",
                "--directory",
                workspace.toString(),
                "--target",
                "linux-x64",
                "--install-root",
                tempDir.resolve("toolchains").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("source: [toolchain.java]"));
        assertTrue(result.stdout().contains("source: managed"));
        assertTrue(result.stdout().contains("javaHome: " + store.javaHome(locked)));
    }

    @Test
    void globalUseSyncAndStatusUseUserConfigAndGlobalLockfile() throws IOException {
        Path configPath = tempDir.resolve("home/config.toml");
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = locked();
        install(store, locked);

        var use = execute(
                "toolchain",
                "global",
                "use",
                "java",
                "21",
                "--graalvm",
                "--native-image",
                "--config",
                configPath.toString());
        var sync = execute(
                "toolchain",
                "sync",
                "--global",
                "--config",
                configPath.toString(),
                "--target",
                "linux-x64",
                "--install-root",
                tempDir.resolve("toolchains").toString());
        var status = execute(
                "toolchain",
                "status",
                "--global",
                "--config",
                configPath.toString(),
                "--target",
                "linux-x64",
                "--install-root",
                tempDir.resolve("toolchains").toString());

        assertEquals(0, use.exitCode(), use.stderr());
        assertTrue(Files.readString(configPath).contains("[defaults.toolchain.java]"));
        assertEquals(0, sync.exitCode(), sync.stderr());
        assertTrue(sync.stdout().contains("Synced Java toolchain"));
        Path globalLockfile = configPath.getParent().resolve("global-toolchains.lock");
        String globalLock = Files.readString(globalLockfile);
        assertEquals(4, countJavaLocks(globalLock));
        assertTrue(globalLock.contains("request.distribution = \"graalvm-community\""));
        assertEquals(0, status.exitCode(), status.stderr());
        assertTrue(status.stdout().contains("source: global default"));
        assertTrue(status.stdout().contains("javaHome: " + store.javaHome(locked)));
    }

    @Test
    void installExplicitJavaToolchainWithoutProjectConfig() throws IOException {
        Path configPath = tempDir.resolve("home/install-config.toml");
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = locked();
        install(store, locked);

        var result = execute(
                "toolchain",
                "install",
                "java",
                "21",
                "--graalvm",
                "--native-image",
                "--config",
                configPath.toString(),
                "--target",
                "linux-x64",
                "--install-root",
                tempDir.resolve("toolchains").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Installed Java toolchain"));
        assertTrue(result.stdout().contains("java graalvm-community 21"));
        assertTrue(result.stdout().contains("Managed Java toolchain is already installed"));
        Path globalLockfile = configPath.getParent().resolve("global-toolchains.lock");
        String globalLock = Files.readString(globalLockfile);
        assertEquals(4, countJavaLocks(globalLock));
        assertTrue(globalLock.contains("artifact.sha256 = \"b048069aaa3a99b84f5b957b162cc181a32a4330cbc35402766363c5be76ae48\""));
    }

    @Test
    void availableShowsBundledCatalogAndInstalledStatus() throws IOException {
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = locked();
        install(store, locked);

        var result = execute(
                "toolchain",
                "available",
                "--install-root",
                tempDir.resolve("toolchains").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Available Java toolchains"));
        assertTrue(result.stdout().contains("temurin 21 -> 21.0.11+10"));
        assertTrue(result.stdout().contains("graalvm-community 21 -> 21.0.2"));
        assertTrue(result.stdout().contains("features: native-image"));
        assertTrue(result.stdout().contains("linux-x64: installed at " + store.javaHome(locked)));
    }

    @Test
    void listShowsProjectGlobalLocksAndInstalledToolchains() throws IOException {
        Path project = tempDir.resolve("list-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [toolchain.java]
                version = 21
                distribution = "graalvm-community"
                features = ["native-image"]
                """);
        LockedJavaToolchain locked = locked();
        new ToolchainLockfileService().writeJava(project.resolve("zolt.lock"), locked);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        install(store, locked);
        Path configPath = tempDir.resolve("home/list-config.toml");
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, """
                version = 1

                [defaults.toolchain.java]
                version = "21"
                distribution = "graalvm-community"
                features = ["native-image"]
                policy = "prefer-managed"
                """);
        new ToolchainLockfileService().writeJava(configPath.getParent().resolve("global-toolchains.lock"), locked);

        var result = execute(
                "toolchain",
                "list",
                "--directory",
                project.toString(),
                "--config",
                configPath.toString(),
                "--target",
                "linux-x64",
                "--install-root",
                tempDir.resolve("toolchains").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Java toolchains"));
        assertTrue(result.stdout().contains("active"));
        assertTrue(result.stdout().contains("project: graalvm-community 21 features=native-image policy=prefer-managed -> ok managed"));
        assertTrue(result.stdout().contains("global"));
        assertTrue(result.stdout().contains("request: graalvm-community 21 features=native-image policy=prefer-managed"));
        assertTrue(result.stdout().contains("project lock"));
        assertTrue(result.stdout().contains("global lock"));
        assertTrue(result.stdout().contains("installed"));
        assertTrue(result.stdout().contains("graalvm-community 21.0.2 linux-x64 features=native-image at " + store.javaHome(locked)));
    }

    @Test
    void syncRequiresExplicitJavaToolchainTable() throws IOException {
        Path project = tempDir.resolve("project-without-toolchain");
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);

        var result = execute(
                "toolchain",
                "sync",
                "--directory",
                project.toString(),
                "--target",
                "linux-x64");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Toolchain sync needs an explicit [toolchain.java] table."));
    }

    private static LockedJavaToolchain locked() {
        JavaToolchainRequest request = new JavaToolchainRequest(
                "21",
                JavaDistribution.GRAALVM_COMMUNITY,
                Set.of(JavaFeature.NATIVE_IMAGE),
                ToolchainPolicy.PREFER_MANAGED);
        return new LockedJavaToolchain(
                "java-graalvm-community-21-native-image",
                request,
                HostPlatform.parse("linux-x64"),
                "21.0.2",
                JavaDistribution.GRAALVM_COMMUNITY,
                "builtin:java-graalvm-community-21-native-image",
                "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-21.0.2/graalvm-community-jdk-21.0.2_linux-x64_bin.tar.gz",
                "b048069aaa3a99b84f5b957b162cc181a32a4330cbc35402766363c5be76ae48",
                new JavaToolchainLayout(
                        ".",
                        "bin/java",
                        "bin/javac",
                        "bin/jar",
                        "lib/svm/bin/native-image"));
    }

    private static int countJavaLocks(String content) {
        return content.split("\\[\\[toolchain\\.java]]", -1).length - 1;
    }

    private static void install(ToolchainStore store, LockedJavaToolchain locked) throws IOException {
        tool(store.java(locked));
        tool(store.javac(locked));
        tool(store.jar(locked));
        tool(store.nativeImage(locked).orElseThrow());
    }

    private static void tool(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "");
        path.toFile().setExecutable(true);
    }
}
