package sh.zolt.cli.command.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.maven.metadata.VersionDiscovery;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
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

final class WorkspaceRootAliasExactUpdateTest {
    private static final ManifestProjectConfigLoader LOADER = new ManifestProjectConfigLoader();

    @TempDir
    private Path tempDir;

    @Test
    void exactDryRunReportsEveryMemberGovernedByTheRootAlias() throws IOException {
        Path root = writeWorkspace();
        Result result = run(root);

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains(
                "apps/api/zolt.toml:[dependencies].com.example:one"), result.stdout());
        assertTrue(result.stdout().contains(
                "modules/core/zolt.toml:[dependencies.test].com.example:two"), result.stdout());
        assertTrue(result.stdout().contains("updates 2 referencing coordinate(s)"), result.stdout());
    }

    @Test
    void rootMemberAliasReportsReferencesFromSiblingMembers() throws IOException {
        Path root = writeRootMemberWorkspace();
        Result result = run(root);

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("[dependencies].com.example:root"), result.stdout());
        assertTrue(result.stdout().contains(
                "modules/core/zolt.toml:[dependencies].com.example:core"), result.stdout());
        assertTrue(result.stdout().contains("updates 2 referencing coordinate(s)"), result.stdout());
    }

    private Result run(Path root) {
        UpdateTarget target = new UpdateTargetCatalog()
                .collect(LOADER.document(root.resolve("zolt.toml")).authored(), "zolt.toml", "zolt.lock")
                .stream()
                .filter(candidate -> candidate.surface() == OutdatedSurface.VERSION_ALIAS)
                .findFirst()
                .orElseThrow();
        VersionDiscovery forbiddenDiscovery = (repositories, group, artifact, offline) -> {
            throw new AssertionError("Exact update must not perform metadata discovery.");
        };
        UpdateCommand command = new UpdateCommand(
                new ManifestMutationServices(),
                null,
                new UpdateEngine(forbiddenDiscovery));
        CommandLine commandLine = new CommandLine(command).setCaseInsensitiveEnumValuesAllowed(true);
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        commandLine.setOut(new PrintWriter(stdout));
        commandLine.setErr(new PrintWriter(stderr));

        int exitCode = commandLine.execute(
                "--target-id", target.targetId().toString(),
                "--to", "1.1.0",
                "--dry-run",
                "--format", "json",
                "--schema-version", "2",
                "--directory", root.toString());
        return new Result(exitCode, stdout.toString(), stderr.toString());
    }

    private Path writeWorkspace() throws IOException {
        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root.resolve("apps/api"));
        Files.createDirectories(root.resolve("modules/core"));
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "demo"

                [workspace.members]
                include = ["apps/api", "modules/core"]

                [versions]
                shared = "1.0.0"
                """);
        Files.writeString(root.resolve("apps/api/zolt.toml"), """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [dependencies]
                "com.example:one" = { versionRef = "shared" }
                """);
        Files.writeString(root.resolve("modules/core/zolt.toml"), """
                [project]
                name = "core"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [dependencies.test]
                "com.example:two" = { versionRef = "shared" }
                """);
        return root;
    }

    private Path writeRootMemberWorkspace() throws IOException {
        Path root = tempDir.resolve("root-member-workspace");
        Files.createDirectories(root.resolve("modules/core"));
        Files.writeString(root.resolve("zolt.toml"), """
                [project]
                name = "root"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [workspace]
                name = "demo"

                [workspace.members]
                include = [".", "modules/core"]

                [versions]
                shared = "1.0.0"

                [dependencies]
                "com.example:root" = { versionRef = "shared" }
                """);
        Files.writeString(root.resolve("modules/core/zolt.toml"), """
                [project]
                name = "core"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [dependencies]
                "com.example:core" = { versionRef = "shared" }
                """);
        return root;
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
