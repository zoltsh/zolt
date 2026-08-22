package sh.zolt.cli.build;

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

final class RunCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void runBuildsAndRunsConfiguredMainClass() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.println("hello " + args[0] + " " + args[1]);
                    }
                }
                """);

        CommandResult result = execute(
                "run",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--",
                "one",
                "two");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("hello one two"));
        assertTrue(result.stdout().contains("Ran com.example.Main"));
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/Main.class")));
    }

    @Test
    void runAcceptsVisibleProjectDirectoryOption() throws IOException {
        Path projectDir = tempDir.resolve("directory-demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.println("hello " + args[0]);
                    }
                }
                """);

        CommandResult result = execute(
                "run",
                "--directory", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache-directory").toString(),
                "--",
                "directory");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("hello directory"));
        assertTrue(result.stdout().contains("Ran com.example.Main"));
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/Main.class")));
    }

    @Test
    void runUsesModernHumanOutputControlsForZoltSummaryOnly() throws IOException {
        Path projectDir = tempDir.resolve("modern-output-run");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, "package com.example; public final class Main { "
                + "public static void main(String[] args) { System.out.print(\"app output\"); } }\n");
        CommandResult color = execute("--color=always", "run", "--directory", projectDir.toString(),
                "--cache-root", tempDir.resolve("color-cache").toString());
        CommandResult quiet = execute("--quiet", "run", "--directory", projectDir.toString(),
                "--cache-root", tempDir.resolve("quiet-cache").toString());

        assertEquals(0, color.exitCode(), color.stderr());
        assertTrue(color.stdout().contains("app output\n\u001B[32m✔\u001B[0m Ran com.example.Main"));
        assertFalse(color.stdout().contains("\u001B[32mRan com.example.Main"));
        assertEquals(0, quiet.exitCode(), quiet.stderr());
        assertEquals("app output", quiet.stdout());
    }

    @Test
    void runCommandPrintsJsonTimingsWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.println("hello");
                    }
                }
                """);

        CommandResult result = execute(
                "run",
                "--timings",
                "--timings-format", "json",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("hello"));
        assertTrue(result.stdout().contains("Ran com.example.Main"));
        String[] lines = result.stderr().lines().toArray(String[]::new);
        assertEquals(2, lines.length);
        assertTrue(lines[0].contains("\"phase\":\"config read\""));
        assertTrue(lines[0].contains("\"depth\":0"));
        assertTrue(lines[1].contains("\"phase\":\"run application\""));
        assertTrue(lines[1].contains("\"depth\":0"));
        assertTrue(lines[1].contains("\"mainClass\":\"com.example.Main\""));
        assertTrue(lines[1].contains("\"mainSourceFiles\":\"1\""));
        assertTrue(lines[1].contains("\"mainCompilationSkipped\":\"false\""));
        assertTrue(lines[1].contains("\"mainCompilationMode\":\"full\""));
        assertTrue(lines[1].contains("\"mainIncrementalFallbackReason\":\"missing-state\""));
        assertTrue(lines[1].contains("\"mainSourcesRecompiled\":\"1\""));
        assertTrue(lines[1].contains("\"mainAbiChangedClasses\":\"0\""));
        assertTrue(lines[1].contains("\"outputBytes\""));
    }

    @Test
    void runReportsSignalTerminationAsCleanStop() throws IOException {
        Path projectDir = tempDir.resolve("sigterm-demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.println("running");
                        System.exit(143);
                    }
                }
                """);

        CommandResult result = execute(
                "run",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("sigterm-cache").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("running"));
        assertTrue(result.stdout().contains("Stopped com.example.Main (signal 15)"));
        assertFalse(result.stdout().contains("Ran com.example.Main"));
        assertFalse(result.stderr().contains("error:"));
        assertFalse(result.stdout().contains("Check the application output and try again"));
        assertFalse(result.stderr().contains("Check the application output and try again"));
    }

    @Test
    void runReportsInterruptSignalAsCleanStop() throws IOException {
        Path projectDir = tempDir.resolve("sigint-demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                        System.exit(130);
                    }
                }
                """);

        CommandResult result = execute(
                "run",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("sigint-cache").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Stopped com.example.Main (signal 2)"));
        assertFalse(result.stderr().contains("error:"));
    }

    @Test
    void runReportsGenuineNonZeroExitAsUserError() throws IOException {
        Path projectDir = tempDir.resolve("failure-demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                        System.exit(1);
                    }
                }
                """);

        CommandResult result = execute(
                "run",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("failure-cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("java exited with code 1"));
        assertTrue(result.stderr().contains("Check the application output and try again"));
        assertFalse(result.stdout().contains("Stopped com.example.Main"));
    }

    @Test
    void runReportsMissingMainClassClearly() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfigWithoutMain(projectDir, "https://repo.maven.apache.org/maven2");

        CommandResult result = execute(
                "run",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("No main class is configured"));
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

                [repositories]
                central = false

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

                [repositories]
                central = false

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
