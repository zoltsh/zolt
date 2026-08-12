package sh.zolt.cli.command.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.maven.metadata.MetadataDiscovery;
import sh.zolt.maven.metadata.VersionDiscovery;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.toml.ZoltTomlWriter;
import sh.zolt.update.UpdateEngine;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

final class WorkspacePolicyMirroredPlatformUpdateTest {
    @TempDir
    private Path tempDir;

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("mirroredModes")
    void policyUpdateSkipsMirroredPlatforms(String label, String memberPolicy, List<String> mode) throws IOException {
        Path root = writeWorkspace(tempDir.resolve(label), true, memberPolicy);
        Path rootManifest = root.resolve("zolt.toml");
        Path memberManifest = root.resolve("apps/api/zolt.toml");
        String rootOriginal = Files.readString(rootManifest);
        String memberOriginal = Files.readString(memberManifest);

        Result result = run(memberManifest.getParent(), () -> {}, mode);

        assertEquals(0, result.exitCode(), () -> result.stdout() + result.stderr());
        assertTrue(result.stdout().contains("\"edits\": []"), result.stdout());
        assertTrue(result.stdout().contains("consolidate the declaration"), result.stdout());
        assertEquals(rootOriginal, Files.readString(rootManifest));
        assertEquals(memberOriginal, Files.readString(memberManifest));
    }

    @Test
    void policyUpdateRejectsMirrorIntroducedAfterPlanning() throws IOException {
        Path root = writeWorkspace(tempDir.resolve("concurrent"), false, literalPlatform());
        Path rootManifest = root.resolve("zolt.toml");
        Path memberManifest = root.resolve("apps/api/zolt.toml");
        String memberOriginal = Files.readString(memberManifest);
        String rootConcurrent = Files.readString(rootManifest) + """

                [platforms]
                "org.junit:junit-bom" = "5.10.2"
                """;

        Result result = run(
                memberManifest.getParent(),
                () -> writeUnchecked(rootManifest, rootConcurrent),
                List.of("--no-resolve"));

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("not updateable"), result.stdout());
        assertTrue(result.stdout().contains("consolidate the declaration"), result.stdout());
        assertEquals(rootConcurrent, Files.readString(rootManifest));
        assertEquals(memberOriginal, Files.readString(memberManifest));
    }

    private static Stream<Arguments> mirroredModes() {
        return Stream.of(
                Arguments.of("literal-dry-run", literalPlatform(), List.of("--dry-run", "--no-resolve")),
                Arguments.of("literal-no-resolve", literalPlatform(), List.of("--no-resolve")),
                Arguments.of("literal-resolve", literalPlatform(), List.of()),
                Arguments.of("alias-dry-run", aliasPlatform(), List.of("--dry-run", "--no-resolve")),
                Arguments.of("alias-no-resolve", aliasPlatform(), List.of("--no-resolve")),
                Arguments.of("alias-resolve", aliasPlatform(), List.of()));
    }

    private static String literalPlatform() {
        return """
                [platforms]
                "org.junit:junit-bom" = "5.10.2"
                """;
    }

    private static String aliasPlatform() {
        return """
                [versions]
                junit = "5.10.2"

                [platforms]
                "org.junit:junit-bom" = { versionRef = "junit" }
                """;
    }

    private static Path writeWorkspace(Path root, boolean rootPlatform, String memberPolicy) throws IOException {
        Files.createDirectories(root.resolve("apps/api"));
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "mirrored"
                members = ["apps/api"]
                %s
                """.formatted(rootPlatform ? "\n" + literalPlatform() : ""));
        Files.writeString(root.resolve("apps/api/zolt.toml"), """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                %s
                """.formatted(memberPolicy));
        return root;
    }

    private static Result run(Path member, Runnable beforeExecution, List<String> mode) {
        VersionDiscovery discovery = (repositories, group, artifact, offline) -> new MetadataDiscovery(
                true,
                List.of("5.10.2", "5.11.4"),
                Map.of("5.10.2", "central", "5.11.4", "central"),
                List.of());
        UpdateCommand command = new UpdateCommand(
                new ZoltTomlParser(),
                new ZoltTomlWriter(),
                null,
                new UpdateEngine(discovery),
                beforeExecution);
        CommandLine commandLine = new CommandLine(command).setCaseInsensitiveEnumValuesAllowed(true);
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        commandLine.setOut(new PrintWriter(stdout));
        commandLine.setErr(new PrintWriter(stderr));
        List<String> arguments = new ArrayList<>(List.of("--format", "json", "--directory", member.toString()));
        arguments.addAll(mode);
        int exitCode = commandLine.execute(arguments.toArray(String[]::new));
        return new Result(exitCode, stdout.toString(), stderr.toString());
    }

    private static void writeUnchecked(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
