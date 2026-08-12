package sh.zolt.cli.command.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.maven.metadata.VersionDiscovery;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.toml.ZoltTomlWriter;
import sh.zolt.update.OutdatedSurface;
import sh.zolt.update.UpdateEngine;
import sh.zolt.update.UpdateTarget;
import sh.zolt.update.UpdateTargetCatalog;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

final class DependencyExactUpdateIdentityCompatibilityTest {
    @TempDir
    private Path tempDir;

    private final ZoltTomlParser parser = new ZoltTomlParser();

    @Test
    void exactSchemaV2ReportsNoncanonicalSiblingIdentityWithoutWriting() throws IOException {
        Path project = tempDir.resolve("noncanonical-sibling");
        Files.createDirectories(project);
        Path manifest = project.resolve("zolt.toml");
        Files.writeString(manifest, """
                [project]
                name = "noncanonical-sibling"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [dependencies]
                "com.example:lib" = "1.0.0"
                """);
        UpdateTarget target = new UpdateTargetCatalog()
                .collect(parser.parse(manifest), "zolt.toml", "zolt.lock")
                .stream()
                .filter(candidate -> candidate.surface() == OutdatedSurface.DEPENDENCY)
                .findFirst()
                .orElseThrow();
        String decomposed = "cafe\u0301";
        Files.writeString(
                manifest,
                Files.readString(manifest).replace(
                        "\"com.example:lib\" = \"1.0.0\"",
                        "\"com.example:lib\" = \"1.0.0\"\n\"com.example:" + decomposed + "\" = \"1.0.0\""));
        String original = Files.readString(manifest);

        Result result = run(project, target);

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("Update target identifier must use Unicode NFC normalization"));
        assertEquals(original, Files.readString(manifest));
    }

    private Result run(Path project, UpdateTarget target) {
        VersionDiscovery forbiddenDiscovery = (repositories, group, artifact, offline) -> {
            throw new AssertionError("Exact update must not perform metadata discovery.");
        };
        UpdateCommand command = new UpdateCommand(
                parser,
                new ZoltTomlWriter(),
                null,
                new UpdateEngine(forbiddenDiscovery));
        CommandLine commandLine = new CommandLine(command).setCaseInsensitiveEnumValuesAllowed(true);
        commandLine.setExecutionExceptionHandler((exception, parsedCommandLine, parseResult) -> 1);
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        commandLine.setOut(new PrintWriter(stdout));
        commandLine.setErr(new PrintWriter(stderr));
        int exitCode = commandLine.execute(
                "--target-id", target.targetId().toString(),
                "--to", "1.1.0",
                "--format", "json",
                "--schema-version", "2",
                "--no-resolve",
                "--directory", project.toString());
        return new Result(exitCode, stdout.toString(), stderr.toString());
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
