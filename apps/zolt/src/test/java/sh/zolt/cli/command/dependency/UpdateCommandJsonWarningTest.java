package sh.zolt.cli.command.dependency;

import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.maven.metadata.MetadataDiscovery;
import sh.zolt.maven.metadata.VersionDiscovery;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import sh.zolt.update.UpdateEngine;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** JSON updates preserve source while keeping STDOUT exclusively machine-readable. */
final class UpdateCommandJsonWarningTest {
    private static final ManifestMutationServices MANIFESTS = new ManifestMutationServices();
    private static final ManifestProjectConfigLoader LOADER = new ManifestProjectConfigLoader();

    @TempDir
    private Path tempDir;

    @Test
    void jsonModePreservesCommentsAndKeepsStdoutValidJson() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Files.createDirectories(projectDir);
        Path configPath = projectDir.resolve("zolt.toml");
        Files.writeString(configPath, memberConfig("demo") + """

                [repositories]
                central = "http://127.0.0.1:1/maven2"

                # pin lib for reproducibility
                [dependencies]
                "com.example:lib" = "1.0.0"

                [coverage]
                line = 88 # release floor

                [toolchain.java]
                distribution = "temurin"

                [publish.central]
                tokenEnv = "ZOLT_CENTRAL_TOKEN"
                mode = "manual"

                [tasks.verify-all]
                run = ["zolt", "check"]
                """);

        Result result = runUpdateJson(projectDir, discovery("com.example", "lib", "1.0.0", "1.1.0"));

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("", result.stderr());
        assertFalse(result.stdout().contains("Warning"), result.stdout());
        assertFalse(result.stdout().contains("comments"), result.stdout());
        // STDOUT is the plan JSON and nothing else.
        String stdout = result.stdout().strip();
        assertTrue(stdout.startsWith("{"), stdout);
        assertTrue(stdout.endsWith("}"), stdout);
        assertTrue(stdout.contains("\"command\": \"update\""), stdout);
        assertTrue(stdout.contains("\"from\": \"1.0.0\""), stdout);
        assertTrue(stdout.contains("\"to\": \"1.1.0\""), stdout);
        String rewritten = Files.readString(configPath);
        assertTrue(rewritten.contains("\"com.example:lib\" = \"1.1.0\""), rewritten);
        assertTrue(rewritten.contains("# pin lib for reproducibility"), rewritten);
        assertTrue(rewritten.contains("[coverage]\nline = 88 # release floor"), rewritten);
        assertTrue(rewritten.contains("[toolchain.java]\ndistribution = \"temurin\""), rewritten);
        assertTrue(
                rewritten.contains("[publish.central]\ntokenEnv = \"ZOLT_CENTRAL_TOKEN\"\nmode = \"manual\""),
                rewritten);
        assertTrue(rewritten.contains("[tasks.verify-all]\nrun = [\"zolt\", \"check\"]"), rewritten);
    }

    @Test
    void jsonDryRunNeitherWarnsNorRewrites() throws IOException {
        Path projectDir = tempDir.resolve("dry");
        Files.createDirectories(projectDir);
        Path configPath = projectDir.resolve("zolt.toml");
        String original = memberConfig("dry") + """

                [repositories]
                central = "http://127.0.0.1:1/maven2"

                # keep this comment
                [dependencies]
                "com.example:lib" = "1.0.0"

                """;
        Files.writeString(configPath, original);

        Result result = runUpdateJsonDryRun(projectDir, discovery("com.example", "lib", "1.0.0", "1.1.0"));

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().strip().startsWith("{"), result.stdout());
        assertEquals(original, Files.readString(configPath));
    }

    /**
     * A final dependency coordinate is exact ASCII {@code group:artifact} (design §9.3), so a
     * decomposed identifier is rejected by the parser instead of reaching the automation boundary.
     */
    @Test
    void policyUpdateRejectsNonAsciiDependencyIdentifier() throws IOException {
        String decomposed = "cafe\u0301";
        Path projectDir = tempDir.resolve("unicode-coordinate");
        Files.createDirectories(projectDir);
        Path configPath = projectDir.resolve("zolt.toml");
        Files.writeString(configPath, memberConfig("unicode-coordinate") + """

                [dependencies]
                "com.example:%s" = "1.0.0"
                """.formatted(decomposed));

        Result result = runUpdateJson(
                projectDir,
                discovery("com.example", decomposed, "1.0.0", "1.1.0"));

        assertEquals(1, result.exitCode());
        assertTrue(
                (result.stdout() + result.stderr()).contains("Invalid dependency coordinate"),
                result.stdout() + result.stderr());
    }

    @Test
    void updateRefusesAStalePlanWithoutOverwritingTheConcurrentEdit() throws IOException {
        Path projectDir = tempDir.resolve("stale");
        Files.createDirectories(projectDir);
        Path configPath = projectDir.resolve("zolt.toml");
        String original = memberConfig("stale") + """

                [repositories]
                central = "http://127.0.0.1:1/maven2"

                [dependencies]
                "com.example:lib" = "1.0.0"
                """;
        String concurrent = original.replace(
                "\"com.example:lib\" = \"1.0.0\"",
                "\"com.example:lib\" = \"1.0.1\" # concurrent edit");
        Files.writeString(configPath, original);
        VersionDiscovery discovery = (repositories, group, artifact, offline) -> {
            try {
                Files.writeString(configPath, concurrent);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return discovery("com.example", "lib", "1.0.0", "1.1.0")
                    .discover(repositories, group, artifact, offline);
        };

        Result result = runUpdateJson(projectDir, discovery);

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("changed while dependency updates were being planned"));
        assertEquals(concurrent, Files.readString(configPath));
    }

    private Result runUpdateJson(Path projectDir, VersionDiscovery discovery) {
        return run(projectDir, discovery, "--format", "json", "--no-resolve", "--directory", projectDir.toString());
    }

    private Result runUpdateJsonDryRun(Path projectDir, VersionDiscovery discovery) {
        return run(
                projectDir,
                discovery,
                "--format", "json", "--dry-run", "--no-resolve", "--directory", projectDir.toString());
    }

    private Result run(Path projectDir, VersionDiscovery discovery, String... args) {
        // resolveService is never touched because every invocation passes --no-resolve.
        UpdateCommand command =
                new UpdateCommand(
                MANIFESTS, null, new UpdateEngine(discovery));
        // Match ZoltCli.newCommandLine() so `--format json` (lowercase) parses like the real CLI.
        CommandLine commandLine = new CommandLine(command).setCaseInsensitiveEnumValuesAllowed(true);
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        commandLine.setOut(new PrintWriter(stdout));
        commandLine.setErr(new PrintWriter(stderr));
        int exitCode = commandLine.execute(args);
        return new Result(exitCode, stdout.toString(), stderr.toString());
    }

    private static VersionDiscovery discovery(String groupId, String artifactId, String... versions) {
        Map<String, String> sourceByVersion = new java.util.LinkedHashMap<>();
        for (String version : versions) {
            sourceByVersion.putIfAbsent(version, "central");
        }
        MetadataDiscovery listing = new MetadataDiscovery(true, List.of(versions), sourceByVersion, List.of());
        MetadataDiscovery missing = new MetadataDiscovery(false, List.of(), Map.of(), List.of());
        return (repositories, group, artifact, offline) ->
                group.equals(groupId) && artifact.equals(artifactId) ? listing : missing;
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
