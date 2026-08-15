package sh.zolt.cli.build;

import sh.zolt.cli.CliTestRepository;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.writeCurrentProjectLock;
import static sh.zolt.cli.build.BuildCommandTestSupport.enableQuarkus;
import static sh.zolt.cli.build.BuildCommandTestSupport.writeMainSource;
import static sh.zolt.cli.build.BuildCommandTestSupport.writeProjectConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void buildRejectsStaleExistingLockfileBeforeCompiling() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "app", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>app</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            repository.addArtifact("com.example", "extra", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>extra</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path projectDir = tempDir.resolve("build-stale-lock");
            Path cacheRoot = tempDir.resolve("build-stale-lock-cache");
            writeProjectConfig(projectDir, repository.baseUri().toString(), Map.of("com.example:app", "1.0.0"));
            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cacheRoot.toString());
            Path mainSource = projectDir.resolve("src/main/java/com/example/Main.java");
            Files.createDirectories(mainSource.getParent());
            Files.writeString(mainSource, """
                    package com.example;

                    public final class Main {
                    }
                    """);
            writeProjectConfig(projectDir, repository.baseUri().toString(), Map.of(
                    "com.example:app", "1.0.0",
                    "com.example:extra", "1.0.0"));

            CommandResult result = execute(
                    "build",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cacheRoot.toString());

            assertEquals(0, resolve.exitCode());
            assertEquals(1, result.exitCode());
            assertTrue(result.stderr().contains("zolt.lock is out of date"));
            assertTrue(result.stderr().contains("Run `zolt resolve` to refresh it"));
            assertFalse(Files.exists(projectDir.resolve("target/classes/com/example/Main.class")));
        }
    }

    @Test
    void buildResolvesMissingLockfileAndCompilesMainSources() throws IOException {
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
                "--progress=always",
                "build",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Resolved dependencies because zolt.lock was missing"));
        assertTrue(result.stdout().contains("✔ Compiled 1 main source files"));
        assertTrue(result.stdout().contains("→ wrote " + projectDir.resolve("target/classes")));
        assertTrue(result.stderr().contains("Building project..."));
        assertTrue(result.stderr().contains("Built 1 main source files"));
        assertTrue(Files.exists(projectDir.resolve("zolt.lock")));
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/Main.class")));
    }

    @Test
    void buildVerbosePrintsProvenanceBlockWithoutGitLinesOutsideARepository() throws IOException {
        Path projectDir = tempDir.resolve("verbose-demo");
        Path cacheRoot = tempDir.resolve("verbose-cache");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                }
                """);
        CommandResult resolve = execute(
                "resolve",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString());

        CommandResult result = execute(
                "-v",
                "build",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, resolve.exitCode(), resolve.stderr());
        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("✔ Compiled 1 main source files"));
        assertTrue(result.stdout().contains("  provenance"));
        assertFalse(result.stdout().contains("commit    "));
        assertTrue(result.stdout().contains("built     "));
        assertTrue(result.stdout().contains("toolchain zolt "));
        assertTrue(result.stdout().contains("inputs    sha256:"));
    }

    @Test
    void buildQuietSuppressesVerboseProvenance() throws IOException {
        Path projectDir = tempDir.resolve("quiet-verbose-demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeCurrentProjectLock(projectDir);
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                }
                """);

        CommandResult result = execute(
                "--quiet",
                "-v",
                "build",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("quiet-verbose-cache").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("", result.stdout());
    }

    @Test
    void buildAcceptsVisibleProjectDirectoryOption() throws IOException {
        Path projectDir = tempDir.resolve("directory-build");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeCurrentProjectLock(projectDir);
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                }
                """);

        CommandResult result = execute(
                "build",
                "--directory", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Compiled 1 main source files"));
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/Main.class")));
    }

    @Test
    void buildOfflineUsesExistingLockfileAndCache() throws IOException {
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
                "build",
                "--offline",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Compiled 1 main source files"));
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/Main.class")));
    }

    @Test
    void buildColorsOnlyHumanSummaryLeadFragmentsWhenForced() throws IOException {
        Path projectDir = tempDir.resolve("color-demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeCurrentProjectLock(projectDir);
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                }
                """);

        CommandResult result = execute(
                "--color=always",
                "--progress=always",
                "build",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());
        CommandResult noOp = execute(
                "--color=always",
                "build",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\u001B[36mBuilding\u001B[0m demo"));
        assertFalse(result.stdout().contains("\u001B[36mBuilding demo"));
        assertTrue(result.stdout().contains("\u001B[32m✔\u001B[0m Compiled 1 main source files"));
        assertTrue(result.stdout().contains("\u001B[36m→\u001B[0m wrote \u001B[36m"
                + projectDir.resolve("target/classes") + "\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[32mCompiled 1 main source files\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[32mWrote classes to "));
        assertTrue(result.stderr().contains("\u001B[36mBuilding\u001B[0m project..."));
        assertTrue(result.stderr().contains("\u001B[32mBuilt\u001B[0m 1 main source files"));
        assertFalse(result.stderr().contains("\u001B[36mBuilding project...")
                || result.stderr().contains("\u001B[32mBuilt 1 main source files"));
        assertEquals(0, noOp.exitCode());
        assertTrue(noOp.stdout().contains("\u001B[32mSkipped\u001B[0m main compilation; inputs are unchanged"));
        assertFalse(noOp.stdout().contains("\u001B[32mSkipped main compilation; inputs are unchanged\u001B[0m"));
    }

}
