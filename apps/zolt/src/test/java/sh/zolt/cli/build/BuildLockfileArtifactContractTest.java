package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.build.BuildCommandTestSupport.writeMainSource;
import static sh.zolt.cli.build.BuildCommandTestSupport.writeProjectConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.cli.CliTestSupport.CommandResult;

/** End-to-end canaries for the version 6 artifact trust boundary in {@code zolt build}. */
final class BuildLockfileArtifactContractTest {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);

    @TempDir
    private Path tempDir;

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedLocks")
    void buildRejectsMalformedVersionSixArtifactMetadata(
            String displayName,
            String packageFields,
            String expectedDiagnostic) throws IOException {
        Path project = prepareProject(displayName);
        Files.writeString(project.resolve("zolt.lock"), lockfile("compile", packageFields));

        CommandResult result = build(project);

        assertEquals(1, result.exitCode(), result.stderr());
        assertTrue(result.stderr().contains(expectedDiagnostic), result.stderr());
        assertTrue(result.stderr().contains("zolt resolve"), result.stderr());
        assertFalse(Files.exists(project.resolve("target/classes/com/example/Main.class")));
    }

    @Test
    void buildValidatesProcessorAndToolArtifactsBeforeCompilation() throws IOException {
        for (String scope : new String[] {"processor", "tool-exec"}) {
            Path project = prepareProject(scope);
            Files.writeString(project.resolve("zolt.lock"), lockfile(
                    scope,
                    "jar = \"blobs/v2/sha256/" + A + "/tool.jar\"\n"));

            CommandResult result = build(project);

            assertEquals(1, result.exitCode(), scope + ": " + result.stderr());
            assertTrue(result.stderr().contains("`jar` and `jarSha256` must be recorded together"));
            assertFalse(Files.exists(project.resolve("target/classes/com/example/Main.class")));
        }
    }

    @Test
    void buildRequiresVersionFiveArtifactPathsToBeMigrated() throws IOException {
        Path project = prepareProject("version-five");
        Files.writeString(project.resolve("zolt.lock"), """
                version = 5

                [[package]]
                id = "com.example:demo"
                version = "1.0.0"
                source = "test"
                scope = "compile"
                direct = true
                jar = "com/example/demo/1.0.0/demo.jar"
                dependencies = []
                """);

        CommandResult result = build(project);

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("version 5 predates the version 6"));
        assertTrue(result.stderr().contains("zolt resolve"));
        assertFalse(Files.exists(project.resolve("target/classes/com/example/Main.class")));
    }

    private static Stream<Arguments> malformedLocks() {
        return Stream.of(
                Arguments.of(
                        "missing-checksum",
                        "jar = \"blobs/v2/sha256/" + A + "/demo.jar\"\n",
                        "`jar` and `jarSha256` must be recorded together"),
                Arguments.of(
                        "missing-path",
                        "jarSha256 = \"" + A + "\"\n",
                        "`jar` and `jarSha256` must be recorded together"),
                Arguments.of(
                        "maven-layout",
                        "jar = \"com/example/demo/1.0.0/demo.jar\"\njarSha256 = \"" + A + "\"\n",
                        "must start with `blobs/v2/sha256/`"),
                Arguments.of(
                        "mismatched-digest",
                        "jar = \"blobs/v2/sha256/" + A + "/demo.jar\"\njarSha256 = \"" + B + "\"\n",
                        "digest directory must equal `jarSha256`"),
                Arguments.of(
                        "incomplete-secondary",
                        "artifact = \"blobs/v2/sha256/" + A + "/demo.properties\"\n"
                                + "artifactType = \"properties\"\n",
                        "secondary artifact must record"));
    }

    private Path prepareProject(String name) throws IOException {
        Path project = tempDir.resolve(name);
        writeProjectConfig(project, "https://repo.maven.apache.org/maven2");
        writeMainSource(project, "package com.example; public final class Main {}\n");
        return project;
    }

    private CommandResult build(Path project) {
        return execute(
                "build",
                "--cwd", project.toString(),
                "--cache-root", tempDir.resolve("cache").toString());
    }

    private static String lockfile(String scope, String packageFields) {
        return """
                version = 6

                [[package]]
                id = "com.example:demo"
                version = "1.0.0"
                source = "test"
                scope = "%s"
                direct = true
                %sdependencies = []
                """.formatted(scope, packageFields);
    }
}
