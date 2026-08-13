package sh.zolt.cli.nativeimage;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
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

final class NativeCommandToolchainTest {
    @TempDir
    private Path tempDir;

    @Test
    void nativeUsesManagedNativeImageFromPinnedToolchain() throws IOException {
        Path projectDir = tempDir.resolve("managed-native-demo");
        writePinnedProject(projectDir, ToolchainPolicy.PREFER_MANAGED);
        source(projectDir);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = locked(ToolchainPolicy.PREFER_MANAGED);
        new ToolchainLockfileService().writeJava(projectDir.resolve("zolt.lock"), locked);
        installManagedToolchain(store, locked);

        CommandResult result = execute(
                "native",
                "--directory", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root", tempDir.resolve("toolchains").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(Files.exists(projectDir.resolve("target/native/demo")));
        String log = Files.readString(projectDir.resolve("target/native/native-image.log"));
        assertTrue(log.contains("executable=" + store.nativeImage(locked).orElseThrow()));
    }

    @Test
    void nativeFailsClearlyWhenStrictPinnedToolchainIsUnsynced() throws IOException {
        Path projectDir = tempDir.resolve("unsynced-native-demo");
        writePinnedProject(projectDir, ToolchainPolicy.REQUIRE_MANAGED);
        source(projectDir);

        CommandResult result = execute(
                "native",
                "--directory", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root", tempDir.resolve("missing-toolchains").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Java toolchain is not ready for Native Image"));
        assertTrue(result.stderr().contains("zolt toolchain status"));
        assertTrue(result.stderr().contains("zolt toolchain sync"));
        assertFalse(Files.exists(projectDir.resolve("target/demo-0.1.0.jar")));
    }

    private static void writePinnedProject(Path projectDir, ToolchainPolicy policy) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                main = "com.example.Main"

                [toolchain.java]
                version = "21"
                distribution = "graalvm-community"
                features = ["native-image"]
                policy = "%s"
                """.formatted(policy.id()));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n\n");
    }

    private static void source(Path projectDir) throws IOException {
        Path source = projectDir.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
    }

    private static LockedJavaToolchain locked(ToolchainPolicy policy) {
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

    private static void installManagedToolchain(ToolchainStore store, LockedJavaToolchain locked) throws IOException {
        tool(store.java(locked));
        tool(store.javac(locked));
        tool(store.jar(locked));
        Path nativeImage = store.nativeImage(locked).orElseThrow();
        Files.createDirectories(nativeImage.getParent());
        NativeCommandTestSupport.writeFakeNativeImage(nativeImage);
    }

    private static void tool(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "");
        path.toFile().setExecutable(true);
    }
}
