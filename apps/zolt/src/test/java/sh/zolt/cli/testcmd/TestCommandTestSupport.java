package sh.zolt.cli.testcmd;

import static sh.zolt.cli.CliTestSupport.memberConfig;
import static sh.zolt.cli.ContentAddressedLockTestSupport.write;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public abstract class TestCommandTestSupport {
    protected static void writeJUnitConsoleLockfile(Path projectDir, Path cacheRoot) throws IOException {
        write(projectDir.resolve("zolt.lock"), cacheRoot, """
                version = 7

                [[package]]
                id = "org.junit.platform:junit-platform-console-standalone"
                version = "1.11.4"
                source = "maven-central"
                scope = "test"
                direct = false
                jar = "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"
                dependencies = []
                """);
    }

    protected static void writeDemoTestSource(Path projectDir) throws IOException {
        Path testSource = projectDir.resolve("src/test/java/com/example/DemoTest.java");
        Files.createDirectories(testSource.getParent());
        Files.writeString(testSource, "package com.example; public final class DemoTest {}\n");
    }

    protected static void writeProjectConfig(Path projectDir, String repositoryUrl) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.Main"

                [repositories]
                central = false

                [repositories.test]
                url = "%s"

                [dependencies]

                [dependencies.test]
                """.formatted(currentJavaMajorVersion(), repositoryUrl));
    }

    protected static void writeMainSource(Path projectDir, String content) throws IOException {
        Path source = projectDir.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }

    protected static void writeProjectDir(Path projectDir) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig(projectDir.getFileName().toString()));
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }
}
