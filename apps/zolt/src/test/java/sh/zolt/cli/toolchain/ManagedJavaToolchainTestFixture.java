package sh.zolt.cli.toolchain;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.writeCurrentProjectLock;

import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toolchain.lock.JavaToolchainLayout;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public final class ManagedJavaToolchainTestFixture {
    private ManagedJavaToolchainTestFixture() {
    }

    public static Path writeProject(Path root, String name) throws IOException {
        Path projectDir = root.resolve(name);
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.Main"

                [toolchain.java]
                version = %s
                distribution = "temurin"
                features = []
                policy = "require-managed"
                """.formatted(name, currentJavaMajorVersion(), currentJavaMajorVersion()));
        writeCurrentProjectLock(projectDir);
        Path source = projectDir.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.println("real main");
                    }
                }
                """);
        return projectDir;
    }

    public static LockedJavaToolchain locked() {
        JavaToolchainRequest request = new JavaToolchainRequest(
                currentJavaMajorVersion(),
                JavaDistribution.TEMURIN,
                Set.<JavaFeature>of(),
                ToolchainPolicy.REQUIRE_MANAGED);
        return new LockedJavaToolchain(
                "java-temurin-" + currentJavaMajorVersion(),
                request,
                HostPlatform.parse("linux-x64"),
                currentJavaMajorVersion(),
                JavaDistribution.TEMURIN,
                "builtin:java-temurin-" + currentJavaMajorVersion(),
                "https://example.test/jdk.tar.gz",
                "0".repeat(64),
                JavaToolchainLayout.standard(false));
    }

    public static void installManagedToolchain(
            ToolchainStore store,
            LockedJavaToolchain locked,
            Path javacMarker) throws IOException {
        javaTool(store.java(locked));
        javacTool(store.javac(locked), javacMarker);
        tool(store.jar(locked));
    }

    private static void javaTool(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                #!/usr/bin/env bash
                set -euo pipefail

                printf 'java=%s\\n' "$0"
                printf 'javaHome=%s\\n' "$JAVA_HOME"
                printf 'pathHead=%s\\n' "${PATH%%:*}"
                printf 'args=%s\\n' "$*"
                """);
        assertTrue(path.toFile().setExecutable(true));
    }

    private static void javacTool(Path path, Path marker) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                #!/usr/bin/env bash
                set -euo pipefail

                printf 'javac=%%s\\n' "$0" >> "%s"
                exec "%s" "$@"
                """.formatted(marker, currentJavac()));
        assertTrue(path.toFile().setExecutable(true));
    }

    private static void tool(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "");
        assertTrue(path.toFile().setExecutable(true));
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }

    private static Path currentJavac() {
        return Path.of(System.getProperty("java.home")).resolve("bin").resolve(executable("javac"));
    }

    private static String executable(String name) {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? name + ".exe"
                : name;
    }
}
