package sh.zolt.cli.command.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.maven.metadata.MetadataDiscovery;
import sh.zolt.maven.metadata.VersionDiscovery;
import sh.zolt.update.OutdatedEngine;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

final class WorkspaceRootAliasOutdatedTest {
    @TempDir
    private Path tempDir;

    @Test
    void rootAliasDiscoversAndNamesEveryMemberReference() throws IOException {
        Path root = writeWorkspace();
        VersionDiscovery discovery = (repositories, group, artifact, offline) -> new MetadataDiscovery(
                true,
                List.of("1.0.0", "1.1.0"),
                Map.of("1.0.0", "central", "1.1.0", "central"),
                List.of());

        OutdatedCommand command =
                new OutdatedCommand(new OutdatedEngine(discovery), new DependencyUpdateScopeResolver());
        CommandLine commandLine = new CommandLine(command).setCaseInsensitiveEnumValuesAllowed(true);
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        commandLine.setOut(new PrintWriter(stdout));
        commandLine.setErr(new PrintWriter(stderr));

        int exitCode = commandLine.execute(
                "--format", "json",
                "--schema-version", "2",
                "--all",
                "--directory", root.toString());

        assertEquals(0, exitCode, stderr.toString());
        String report = stdout.toString();
        assertTrue(report.contains("\"label\": \"workspace-root\""), report);
        assertTrue(report.contains("\"identifier\": \"shared\""), report);
        assertTrue(report.contains("\"selectedInMajor\": \"1.1.0\""), report);
        assertTrue(report.contains(
                "apps/api/zolt.toml:[dependencies].com.example:one"), report);
        assertTrue(report.contains(
                "modules/core/zolt.toml:[dependencies.test].com.example:two"), report);
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
}
