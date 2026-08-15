package sh.zolt.cli.build;

import sh.zolt.cli.CliTestRepository;

import static sh.zolt.cli.build.BuildCommandTestSupport.enableQuarkus;
import static sh.zolt.cli.build.BuildCommandTestSupport.currentJavaMajorVersion;
import static sh.zolt.cli.build.BuildCommandTestSupport.writeMainSource;
import static sh.zolt.cli.build.BuildCommandTestSupport.writeProjectConfig;
import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.writeCurrentProjectLock;
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

final class BuildCommandDiagnosticsTest {
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
            assertTrue(result.stderr().contains("File: zolt.lock"));
            assertTrue(result.stderr().contains("Next: Run `zolt resolve` to refresh it"));
            assertFalse(Files.exists(projectDir.resolve("target/classes/com/example/Main.class")));
        }
    }

    @Test
    void buildReportsInvalidConfigFieldAsErrorBlock() throws IOException {
        Path projectDir = tempDir.resolve("invalid-config-field");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "invalid-config-field"
                version = "0.1.0"
                group = "com.example"
                java = "%s"
                packaging = "jar"
                """.formatted(currentJavaMajorVersion()));

        CommandResult result = execute("build", "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("error: Unknown field [project].packaging in zolt.toml."));
        assertTrue(result.stderr().contains("File: zolt.toml"));
        assertTrue(result.stderr().contains("Field: [project].packaging"));
        assertTrue(result.stderr().contains("Next: Remove it or check the spelling."));
    }

    @Test
    void buildAutoResolveFailureRetryHintNamesBuild() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "root", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>root</artifactId>
                      <version>1.0.0</version>
                      <dependencies>
                        <dependency>
                          <groupId>com.example</groupId>
                          <artifactId>bad</artifactId>
                          <version>[1.0,2.0)</version>
                        </dependency>
                      </dependencies>
                    </project>
                    """);
            Path projectDir = tempDir.resolve("build-unsupported-version");
            writeProjectConfig(
                    projectDir,
                    repository.baseUri().toString(),
                    Map.of("com.example:root", "1.0.0"));

            CommandResult result = execute(
                    "build",
                    "--cwd", projectDir.toString(),
                    "--cache-root", tempDir.resolve("cache-build-unsupported-version").toString());

            assertEquals(1, result.exitCode());
            assertTrue(result.stderr().contains("Unsupported transitive dependency version `[1.0,2.0)`"));
            assertTrue(result.stderr().contains("run `zolt build` again"));
            assertFalse(result.stderr().contains("run `zolt resolve` again"));
        }
    }

    @Test
    void buildRunsQuarkusAugmentationWhenFrameworkIsEnabled() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        enableQuarkus(projectDir);
        writeCurrentProjectLock(projectDir);

        CommandResult result = execute(
                "build",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("Compiled 0 main source files"));
        assertTrue(result.stdout().contains("→ wrote " + projectDir.resolve("target/classes")));
        assertTrue(result.stderr().contains("No Quarkus deployment artifacts were found in zolt.lock"));
        assertTrue(result.stderr().contains("run `zolt resolve`"));
    }

    @Test
    void buildCommandPrintsJsonTimingsWithIncrementalDiagnosticsWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeCurrentProjectLock(projectDir);
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                }
                """);

        CommandResult result = execute(
                "build",
                "--timings",
                "--timings-format", "json",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(0, result.exitCode());
        String[] lines = result.stderr().lines().toArray(String[]::new);
        assertEquals(3, lines.length);
        assertTrue(lines[1].contains("\"phase\":\"compile main\""));
        assertTrue(lines[1].contains("\"mainCompilationMode\":\"full\""));
        assertTrue(lines[1].contains("\"mainIncrementalFallbackReason\":\"missing-state\""));
        assertTrue(lines[1].contains("\"mainSourcesAdded\":\"0\""));
        assertTrue(lines[1].contains("\"mainSourcesChanged\":\"0\""));
        assertTrue(lines[1].contains("\"mainSourcesDeleted\":\"0\""));
        assertTrue(lines[1].contains("\"mainSourcesRecompiled\":\"1\""));
        assertTrue(lines[1].contains("\"mainDependentSourcesRecompiled\":\"0\""));
        assertTrue(lines[1].contains("\"mainClassesDeleted\":\"0\""));
        assertTrue(lines[1].contains("\"mainAbiChangedClasses\":\"0\""));
        assertTrue(lines[1].contains("\"mainPackagePrivateAbiChangedClasses\":\"0\""));
    }

    @Test
    void buildReturnsNonZeroOnCompilationFailure() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    missing
                }
                """);

        CommandResult result = execute(
                "build",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("error: javac failed with exit code"));
        assertTrue(result.stderr().contains("Main.java"));
    }
}
