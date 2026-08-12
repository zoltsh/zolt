package sh.zolt.cli.command.dependency;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.maven.metadata.VersionDiscovery;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.toml.ZoltTomlWriter;
import sh.zolt.update.OutdatedSurface;
import sh.zolt.update.UpdateEngine;
import sh.zolt.update.UpdateTarget;
import sh.zolt.update.UpdateTargetCatalog;
import sh.zolt.workspace.toml.WorkspaceConfigParser;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

final class WorkspaceMirroredPlatformUpdateTest {
    @TempDir
    private Path tempDir;

    @Test
    void literalRootAndMemberMirrorsAreReportedAsBlocked() throws IOException {
        Path root = writeWorkspace(tempDir.resolve("literal"), """
                [platforms]
                "org.junit:junit-bom" = "5.10.2"
                """);

        CommandResult result = outdated(root);

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals(2, occurrences(result.stdout(), "\"updateable\": false"), result.stdout());
        assertEquals(2, occurrences(result.stdout(), "consolidate the declaration"), result.stdout());
    }

    @Test
    void versionRefMemberMirrorBlocksBothRootAndAliasTargets() throws IOException {
        Path root = writeWorkspace(tempDir.resolve("alias"), """
                [versions]
                junit = "5.10.2"

                [platforms]
                "org.junit:junit-bom" = { versionRef = "junit" }
                """);

        CommandResult result = outdated(root);

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals(2, occurrences(result.stdout(), "\"updateable\": false"), result.stdout());
        assertTrue(result.stdout().contains("\"surface\": \"versionAlias\""), result.stdout());
        assertEquals(2, occurrences(result.stdout(), "consolidate the declaration"), result.stdout());
    }

    @Test
    void exactRootMirrorIsRejectedBeforeWritingEitherManifest() throws IOException {
        Path root = writeWorkspace(tempDir.resolve("exact"), """
                [platforms]
                "org.junit:junit-bom" = "5.10.2"
                """);
        Path rootManifest = root.resolve("zolt.toml");
        Path memberManifest = root.resolve("apps/api/zolt.toml");
        String rootOriginal = Files.readString(rootManifest);
        String memberOriginal = Files.readString(memberManifest);
        UpdateTarget rootTarget = new UpdateTargetCatalog()
                .collect(
                        new WorkspaceConfigParser().parseRootConfig(rootManifest),
                        "zolt.toml",
                        "zolt.lock").stream()
                .filter(target -> target.surface() == OutdatedSurface.PLATFORM)
                .findFirst()
                .orElseThrow();

        Result result = exactUpdate(root, rootTarget);

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("not updateable"), result.stdout());
        assertTrue(result.stdout().contains("consolidate the declaration"), result.stdout());
        assertEquals(rootOriginal, Files.readString(rootManifest));
        assertEquals(memberOriginal, Files.readString(memberManifest));
        assertFalse(Files.exists(root.resolve("zolt.lock")));
    }

    @Test
    void exactMemberAliasMirrorIsRejectedBeforeWritingEitherManifest() throws IOException {
        Path root = writeWorkspace(tempDir.resolve("exact-alias"), """
                [versions]
                junit = "5.10.2"

                [platforms]
                "org.junit:junit-bom" = { versionRef = "junit" }
                """);
        Path rootManifest = root.resolve("zolt.toml");
        Path memberManifest = root.resolve("apps/api/zolt.toml");
        String rootOriginal = Files.readString(rootManifest);
        String memberOriginal = Files.readString(memberManifest);
        UpdateTarget aliasTarget = new UpdateTargetCatalog()
                .collect(
                        new ZoltTomlParser().parse(memberManifest),
                        "apps/api/zolt.toml",
                        "zolt.lock").stream()
                .filter(target -> target.surface() == OutdatedSurface.VERSION_ALIAS)
                .findFirst()
                .orElseThrow();

        Result result = exactUpdate(root, aliasTarget);

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("not updateable"), result.stdout());
        assertTrue(result.stdout().contains("consolidate the declaration"), result.stdout());
        assertEquals(rootOriginal, Files.readString(rootManifest));
        assertEquals(memberOriginal, Files.readString(memberManifest));
        assertFalse(Files.exists(root.resolve("zolt.lock")));
    }

    @Test
    void exactRootUpdateRejectsMirrorIntroducedAfterSelection() throws IOException {
        Path root = writeWorkspace(tempDir.resolve("concurrent-mirror"), "");
        Path rootManifest = root.resolve("zolt.toml");
        Path memberManifest = root.resolve("apps/api/zolt.toml");
        String rootOriginal = Files.readString(rootManifest);
        String memberConcurrent = Files.readString(memberManifest) + """

                [platforms]
                "org.junit:junit-bom" = "5.10.2"
                """;
        UpdateTarget rootTarget = new UpdateTargetCatalog()
                .collect(
                        new WorkspaceConfigParser().parseRootConfig(rootManifest),
                        "zolt.toml",
                        "zolt.lock").stream()
                .filter(target -> target.surface() == OutdatedSurface.PLATFORM)
                .findFirst()
                .orElseThrow();

        Result result = exactUpdate(
                root,
                rootTarget,
                () -> writeUnchecked(memberManifest, memberConcurrent));

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("not updateable"), result.stdout());
        assertTrue(result.stdout().contains("consolidate the declaration"), result.stdout());
        assertEquals(rootOriginal, Files.readString(rootManifest));
        assertEquals(memberConcurrent, Files.readString(memberManifest));
        assertFalse(Files.exists(root.resolve("zolt.lock")));
    }

    private static Path writeWorkspace(Path root, String memberPolicy) throws IOException {
        Files.createDirectories(root.resolve("apps/api"));
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "mirrored"
                members = ["apps/api"]

                [platforms]
                "org.junit:junit-bom" = "5.10.2"
                """);
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

    private static CommandResult outdated(Path root) {
        return execute(
                "outdated",
                "--format", "json",
                "--schema-version", "2",
                "--all",
                "--offline",
                "--cwd", root.toString());
    }

    private static Result exactUpdate(Path root, UpdateTarget target) {
        return exactUpdate(root, target, () -> {});
    }

    private static Result exactUpdate(Path root, UpdateTarget target, Runnable beforeExecution) {
        VersionDiscovery forbidden = (repositories, group, artifact, offline) -> {
            throw new AssertionError("Exact update must not perform metadata discovery.");
        };
        UpdateCommand command = new UpdateCommand(
                new ZoltTomlParser(),
                new ZoltTomlWriter(),
                null,
                new UpdateEngine(forbidden),
                beforeExecution);
        CommandLine commandLine = new CommandLine(command).setCaseInsensitiveEnumValuesAllowed(true);
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        commandLine.setOut(new PrintWriter(stdout));
        commandLine.setErr(new PrintWriter(stderr));
        int exitCode = commandLine.execute(
                "--target-id", target.targetId().toString(),
                "--to", "5.11.4",
                "--format", "json",
                "--schema-version", "2",
                "--no-resolve",
                "--directory", root.toString());
        return new Result(exitCode, stdout.toString(), stderr.toString());
    }

    private static void writeUnchecked(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static int occurrences(String value, String fragment) {
        return value.split(java.util.regex.Pattern.quote(fragment), -1).length - 1;
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
