package sh.zolt.cli.packaging;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.writeCurrentProjectLock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageCommandDiagnosticsTest extends PackageCommandTestSupport {
    @TempDir
    private Path tempDir;

    @Test
    void packageCommandPrintsNestedJsonTimingsWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeCurrentProjectLock(projectDir);
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.println("hello");
                    }
                }
                """);

        CommandResult result = execute(
                "package",
                "--timings",
                "--timings-format", "json",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Packaged 1 compiled files as thin jar"));
        String[] lines = result.stderr().lines().toArray(String[]::new);
        assertEquals(4, lines.length);
        assertTrue(lines[0].contains("\"phase\":\"config read\""));
        assertTrue(lines[0].contains("\"depth\":0"));
        assertTrue(lines[1].contains("\"phase\":\"build package inputs\""));
        assertTrue(lines[1].contains("\"depth\":1"));
        assertTrue(lines[1].contains("\"sourceFiles\":\"1\""));
        assertTrue(lines[1].contains("\"mainCompilationSkipped\":\"false\""));
        assertTrue(lines[1].contains("\"mainCompilationMode\":\"full\""));
        assertTrue(lines[1].contains("\"mainIncrementalFallbackReason\":\"missing-state\""));
        assertTrue(lines[1].contains("\"mainSourcesRecompiled\":\"1\""));
        assertTrue(lines[1].contains("\"mainAbiChangedClasses\":\"0\""));
        assertTrue(lines[2].contains("\"phase\":\"assemble package\""));
        assertTrue(lines[2].contains("\"depth\":1"));
        assertTrue(lines[2].contains("\"mode\":\"thin\""));
        assertTrue(lines[2].contains("\"entries\":\"1\""));
        assertTrue(lines[3].contains("\"phase\":\"package\""));
        assertTrue(lines[3].contains("\"depth\":0"));
        assertTrue(lines[3].contains("\"mode\":\"thin\""));
    }

    @Test
    void packageCommandEmitsSparseProgressWhenEnabled() throws IOException {
        Path projectDir = tempDir.resolve("progress-demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeCurrentProjectLock(projectDir);
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.println("hello");
                    }
                }
                """);

        CommandResult result = execute(
                "--progress=always",
                "package",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Packaged 1 compiled files as thin jar"));
        assertTrue(result.stderr().contains("Packaging project..."));
        assertTrue(result.stderr().contains("Packaged " + projectDir.resolve("target/demo-0.1.0.jar")));
    }

    @Test
    void packageProgressColorsOnlyLeadFragmentsWhenForced() throws IOException {
        Path projectDir = tempDir.resolve("color-progress-demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeCurrentProjectLock(projectDir);
        writeMainSource(projectDir, "package com.example; public final class Main {}\n");

        CommandResult result = execute(
                "--color=always",
                "--progress=always",
                "package",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("color-cache").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stderr().contains("\u001B[36mPackaging\u001B[0m project..."));
        assertTrue(result.stderr().contains("\u001B[32mPackaged\u001B[0m " + projectDir.resolve("target/demo-0.1.0.jar")));
        assertFalse(result.stderr().contains("\u001B[36mPackaging project..."));
        assertFalse(result.stderr().contains("\u001B[32mPackaged " + projectDir.resolve("target/demo-0.1.0.jar")));
    }

    @Test
    void packageColorsOnlyHumanSummaryLeadFragmentsWhenForced() throws IOException {
        Path projectDir = tempDir.resolve("color-demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeCurrentProjectLock(projectDir);
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);

        CommandResult result = execute(
                "--color=always",
                "package",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        Path jarPath = projectDir.resolve("target/demo-0.1.0.jar");
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\u001B[32m✔\u001B[0m Packaged 1 compiled files as thin jar"));
        assertTrue(result.stdout().contains("\u001B[32mIncluded\u001B[0m Main-Class manifest entry"));
        assertTrue(result.stdout().contains("\u001B[36m→\u001B[0m wrote \u001B[36m" + jarPath + "\u001B[0m"));
        assertTrue(result.stdout().contains("Run with: "));
        assertTrue(result.stdout().contains("Run with dependencies: "));
        assertFalse(result.stdout().contains("\u001B[32mPackaged 1 compiled files as thin jar\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[32mIncluded Main-Class manifest entry"));
        assertFalse(result.stdout().contains("\u001B[32mWrote archive to "));
        assertFalse(result.stdout().contains("\u001B[32mRun with"));
        assertFalse(result.stdout().contains("\u001B[36mRun with"));
    }

    @Test
    void packageResolvedLockfileNoticeUsesModernHumanOutputControls() throws IOException {
        Path colorProject = tempDir.resolve("color-missing-lock");
        Path quietProject = tempDir.resolve("quiet-missing-lock");
        writeProjectConfig(colorProject, "https://repo.maven.apache.org/maven2");
        writeProjectConfig(quietProject, "https://repo.maven.apache.org/maven2");
        writeMainSource(colorProject, """
                package com.example;

                public final class Main {
                }
                """);
        writeMainSource(quietProject, """
                package com.example;

                public final class Main {
                }
                """);

        CommandResult color = execute(
                "--color=always",
                "package",
                "--directory", colorProject.toString(),
                "--cache-root", tempDir.resolve("color-cache").toString());
        CommandResult quiet = execute(
                "--quiet",
                "package",
                "--directory", quietProject.toString(),
                "--cache-root", tempDir.resolve("quiet-cache").toString());

        assertEquals(0, color.exitCode(), color.stderr());
        assertTrue(color.stdout().contains(
                "\u001B[32mResolved\u001B[0m dependencies because zolt.lock was missing"));
        assertFalse(color.stdout().contains("\u001B[32mResolved dependencies because zolt.lock was missing"));
        assertTrue(color.stdout().contains("\u001B[32m✔\u001B[0m Packaged 1 compiled files as thin jar"));
        assertFalse(color.stdout().contains("\u001B[32mPackaged 1 compiled files as thin jar"));
        assertEquals(0, quiet.exitCode(), quiet.stderr());
        assertEquals("", quiet.stdout());
        assertTrue(Files.exists(quietProject.resolve("zolt.lock")));
        assertTrue(Files.exists(quietProject.resolve("target/demo-0.1.0.jar")));
    }

    @Test
    void packageAcceptsVisibleProjectDirectoryOption() throws IOException {
        Path projectDir = tempDir.resolve("directory-package");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeCurrentProjectLock(projectDir);
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                }
                """);

        CommandResult result = execute(
                "package",
                "--directory", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Packaged 1 compiled files as thin jar"));
        assertTrue(Files.exists(projectDir.resolve("target/demo-0.1.0.jar")));
    }

    @Test
    void packageRejectsUnknownModeOverride() {
        CommandResult result = execute(
                "package",
                "--mode", "ear",
                "--cwd", tempDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Unsupported package mode `ear`"));
        assertTrue(result.stderr().contains("Unsupported: `ear`"));
        assertTrue(result.stderr().contains("thin, spring-boot, war, spring-boot-war, quarkus, uber"));
    }

    @Test
    void packageReturnsNonZeroOnPackagingFailure() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "%s"
                main = "com.example.Main"

                [repositories]
                central = "https://repo.maven.apache.org/maven2"

                [dependencies]

                [test.dependencies]

                [build]
                source = "src/main/java"
                test = "src/test/java"
                output = "classes"
                testOutput = "test-classes"
                """.formatted(currentJavaMajorVersion()));
        writeMainSource(projectDir, "package com.example; public final class Main {}\n");
        Files.writeString(projectDir.resolve("target"), "not a directory");

        CommandResult result = execute(
                "package",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Could not package jar"));
        assertTrue(result.stderr().contains("Check that target/ is writable"));
    }

}
