package sh.zolt.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestSupport.CommandResult;

final class WorkspaceMembersCommandTest {
    @TempDir
    private Path workspace;

    @BeforeEach
    void writeWorkspace() throws IOException {
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                default = ["modules/core"]
                include = ["modules/*", "apps/api", "apps/*"]

                [workspace.project]
                group = "com.example"
                version = "1.0.0"
                java = 21
                """);
        member("apps/api", "api");
        member("modules/core", "core");
    }

    @Test
    void textShowsCompleteMembershipSelectionAndEvidence() {
        CommandResult result = run();

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("""
                Workspace platform
                  manifest: zolt.toml
                  selection: explicit-default
                  selected: modules/core

                Members
                  apps/api
                    manifest: apps/api/zolt.toml
                    project: api
                    matched by: apps/*, apps/api
                  modules/core
                    manifest: modules/core/zolt.toml
                    project: core
                    matched by: modules/*
                """, result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void jsonMatchesTheClosedSchemaV1ByteForByte() {
        CommandResult result = run("--format", "json");

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("""
                {
                  "schemaVersion": 1,
                  "workspace": {
                    "name": "platform",
                    "manifestPath": "zolt.toml",
                    "selection": {
                      "source": "explicit-default",
                      "members": ["modules/core"]
                    },
                    "members": [
                      {
                        "path": "apps/api",
                        "manifestPath": "apps/api/zolt.toml",
                        "projectName": "api",
                        "matchedBy": ["apps/*", "apps/api"]
                      },
                      {
                        "path": "modules/core",
                        "manifestPath": "modules/core/zolt.toml",
                        "projectName": "core",
                        "matchedBy": ["modules/*"]
                      }
                    ],
                    "staleExclusions": []
                  }
                }
                """, result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void explicitSchemaOneIsIdenticalAndNestedStartsFindTheRoot() throws IOException {
        Path nested = Files.createDirectories(workspace.resolve("apps/api/src/main/java"));

        CommandResult implicit = run("--format", "json");
        CommandResult explicit = execute(
                "workspace", "members",
                "--format", "json",
                "--schema-version", "1",
                "--cwd", nested.toString());

        assertEquals(0, explicit.exitCode(), explicit.stderr());
        assertEquals(implicit.stdout(), explicit.stdout());
    }

    @Test
    void schemaSelectionFailsBeforeDiscoveryOrPartialOutput() {
        CommandResult text = run("--schema-version", "1");
        CommandResult unsupported = run("--format", "json", "--schema-version", "2");

        assertEquals(1, text.exitCode());
        assertEquals("", text.stdout());
        assertTrue(text.stderr().contains("--schema-version is available only with --format json"));
        assertEquals(1, unsupported.exitCode());
        assertEquals("", unsupported.stdout());
        assertTrue(unsupported.stderr().contains("Unsupported workspace-members JSON schema version `2`"));
    }

    @Test
    void invalidWorkspaceFailsWithoutPartialJson() throws IOException {
        Files.writeString(workspace.resolve("zolt.toml"), "not valid [toml");

        CommandResult result = run("--format", "json");

        assertEquals(1, result.exitCode());
        assertEquals("", result.stdout());
        assertTrue(result.stderr().contains("Invalid workspace manifest"));
    }

    @Test
    void implicitAllIsVisibleAndLegacyWorkspaceFilesAreNotRecognized() throws IOException {
        Files.writeString(workspace.resolve("zolt.toml"), Files.readString(workspace.resolve("zolt.toml"))
                .replace("default = [\"modules/core\"]\n", ""));
        CommandResult implicitAll = run("--format", "json");

        Path legacy = workspace.resolveSibling("legacy");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("zolt-workspace.toml"), """
                [workspace]
                name = "legacy"

                [workspace.members]
                include = ["apps/api"]
                """);
        CommandResult legacyResult = execute(
                "workspace", "members", "--cwd", legacy.toString());

        assertEquals(0, implicitAll.exitCode(), implicitAll.stderr());
        assertTrue(implicitAll.stdout().contains("\"source\": \"implicit-all\""));
        assertTrue(implicitAll.stdout().contains("\"members\": [\"apps/api\", \"modules/core\"]"));
        assertEquals(1, legacyResult.exitCode());
        assertEquals("", legacyResult.stdout());
        assertTrue(legacyResult.stderr().contains("No final Zolt workspace was found"));
    }

    /**
     * Design §6.2: an exclusion that matched no expanded candidate is allowed but reported. Schema
     * v1 always carries the array so automation reads one closed shape; the text projection adds the
     * line only when there is something to report.
     */
    @Test
    void staleExcludesAreReportedInBothProjections() throws IOException {
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["modules/*"]
                exclude = ["modules/retired", "apps/legacy"]

                [workspace.project]
                group = "com.example"
                version = "1.0.0"
                java = 21
                """);

        CommandResult text = run();
        CommandResult json = run("--format", "json");

        assertEquals(0, text.exitCode(), text.stderr());
        assertEquals("""
                Workspace platform
                  manifest: zolt.toml
                  selection: implicit-all
                  selected: modules/core
                  stale excludes: apps/legacy, modules/retired

                Members
                  modules/core
                    manifest: modules/core/zolt.toml
                    project: core
                    matched by: modules/*
                """, text.stdout());
        assertEquals(0, json.exitCode(), json.stderr());
        assertTrue(
                json.stdout().contains("    \"staleExclusions\": [\"apps/legacy\", \"modules/retired\"]\n"),
                json.stdout());
    }

    @Test
    void rootMemberUsesPortableDotAndRootManifestPaths() throws IOException {
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "rooted"

                [workspace.members]
                include = ["."]

                [workspace.project]
                group = "com.example"
                version = "1.0.0"
                java = 21

                [project]
                name = "root"
                """);

        CommandResult result = run("--format", "json");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("\"path\": \".\""));
        assertTrue(result.stdout().contains("\"manifestPath\": \"zolt.toml\""));
        assertTrue(result.stdout().contains("\"projectName\": \"root\""));
        assertTrue(result.stdout().contains("\"matchedBy\": [\".\"]"));
        assertTrue(result.stdout().contains("\"members\": [\".\"]"));
    }

    @Test
    void rootAndWorkspaceHelpAdvertiseTheCommandPath() {
        CommandResult root = execute("--list");
        CommandResult group = execute("workspace", "--help");

        assertEquals(0, root.exitCode(), root.stderr());
        assertTrue(root.stdout().contains("workspace"));
        assertEquals(0, group.exitCode(), group.stderr());
        assertTrue(group.stdout().contains("members"));
        assertTrue(group.stdout().contains("Inspect the final manifest workspace"));
    }

    private void member(String path, String name) throws IOException {
        Path directory = workspace.resolve(path);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("zolt.toml"), """
                [project]
                name = "%s"
                """.formatted(name));
    }

    private CommandResult run(String... arguments) {
        String[] command = new String[arguments.length + 4];
        command[0] = "workspace";
        command[1] = "members";
        command[2] = "--cwd";
        command[3] = workspace.toString();
        System.arraycopy(arguments, 0, command, 4, arguments.length);
        return execute(command);
    }
}
