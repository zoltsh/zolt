package sh.zolt.cli.packaging;

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

final class RunPackageCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void runPackageBuildsThinJarAndRunsConfiguredMainClass() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.println("packaged " + args[0] + " " + args[1]);
                    }
                }
                """);

        CommandResult result = execute(
                "run-package",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--",
                "one",
                "two");
        CommandResult color = execute("--color=always", "run-package", "--cwd", projectDir.toString(), "--cache-root", tempDir.resolve("color-cache").toString(), "--", "one", "two");
        CommandResult quiet = execute("--quiet", "run-package", "--cwd", projectDir.toString(), "--cache-root", tempDir.resolve("quiet-cache").toString(), "--", "one", "two");

        Path jarPath = projectDir.resolve("target/demo-0.1.0.jar");
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("packaged one two"));
        assertTrue(result.stdout().contains("Ran packaged com.example.Main"));
        assertTrue(result.stdout().contains("→ from " + jarPath));
        assertTrue(color.stdout().contains("packaged one two\n\u001B[32m✔\u001B[0m Ran packaged com.example.Main"));
        assertFalse(color.stdout().contains("\u001B[32mRan packaged com.example.Main"));
        assertEquals("packaged one two\n", quiet.stdout());
        assertTrue(Files.exists(jarPath));
    }

    @Test
    void runPackageAcceptsVisibleProjectDirectoryOption() throws IOException {
        Path projectDir = tempDir.resolve("directory-demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.println("packaged " + args[0]);
                    }
                }
                """);

        CommandResult result = execute(
                "run-package",
                "--directory", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache-directory").toString(),
                "--",
                "directory");

        Path jarPath = projectDir.resolve("target/demo-0.1.0.jar");
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("packaged directory"));
        assertTrue(result.stdout().contains("Ran packaged com.example.Main"));
        assertTrue(result.stdout().contains("→ from " + jarPath));
        assertTrue(Files.exists(jarPath));
    }

    @Test
    void runPackageCommandPrintsJsonTimingsWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.println("packaged");
                    }
                }
                """);

        CommandResult result = execute(
                "run-package",
                "--timings",
                "--timings-format", "json",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("packaged"));
        assertTrue(result.stdout().contains("Ran packaged com.example.Main"));
        String[] lines = result.stderr().lines().toArray(String[]::new);
        assertEquals(2, lines.length);
        assertTrue(lines[0].contains("\"phase\":\"config read\""));
        assertTrue(lines[0].contains("\"depth\":0"));
        assertTrue(lines[1].contains("\"phase\":\"run packaged application\""));
        assertTrue(lines[1].contains("\"depth\":0"));
        assertTrue(lines[1].contains("\"mode\":\"thin\""));
        assertTrue(lines[1].contains("\"entries\":\"1\""));
        assertTrue(lines[1].contains("\"hasMainClass\":\"true\""));
        assertTrue(lines[1].contains("\"mainClass\":\"com.example.Main\""));
        assertTrue(lines[1].contains("\"outputBytes\""));
    }

    @Test
    void runPackageReportsMissingMainClassClearly() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfigWithoutMain(projectDir, "https://repo.maven.apache.org/maven2");

        CommandResult result = execute(
                "run-package",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("No main class is configured"));
        assertTrue(result.stderr().contains("[project].main"));
    }

    private static void writeProjectConfig(Path projectDir, String repositoryUrl) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.Main"

                [repositories.test]
                url = "%s"

                [dependencies]

                [dependencies.test]
                """.formatted(currentJavaMajorVersion(), repositoryUrl));
    }

    private static void writeProjectConfigWithoutMain(Path projectDir, String repositoryUrl) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [repositories.test]
                url = "%s"

                [dependencies]

                [dependencies.test]
                """.formatted(currentJavaMajorVersion(), repositoryUrl));
    }

    private static void writeMainSource(Path projectDir, String content) throws IOException {
        Path source = projectDir.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
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
