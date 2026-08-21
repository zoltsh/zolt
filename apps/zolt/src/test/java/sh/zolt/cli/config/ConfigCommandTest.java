package sh.zolt.cli.config;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code zolt config show} reports one explicitly selected manifest view (design §20.2). */
final class ConfigCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void bareShowFailsWithUsageInsteadOfChoosingAView() throws IOException {
        writeStandalone();

        CommandResult result = execute("config", "show", "--directory", tempDir.toString());

        // Exit code 2 is this CLI's invalid-input code: the bare command is a usage failure, not a
        // command that silently picked a view.
        assertEquals(2, result.exitCode());
        assertEquals("", result.stdout());
        assertTrue(
                result.stderr().contains("requires exactly one of `--manifest` and `--effective`"),
                result.stderr());
        assertTrue(result.stderr().contains("`--manifest` reports authored values"), result.stderr());
    }

    @Test
    void bothViewsAreRejectedAsMutuallyExclusive() throws IOException {
        writeStandalone();

        CommandResult result = execute(
                "config", "show", "--manifest", "--effective", "--directory", tempDir.toString());

        assertEquals(2, result.exitCode());
        assertTrue(
                result.stderr().contains("requires exactly one of `--manifest` and `--effective`"),
                result.stderr());
    }

    @Test
    void manifestViewReportsAuthoredValuesOnly() throws IOException {
        writeStandalone();

        CommandResult result = execute(
                "config", "show", "--manifest", "--directory", tempDir.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("""
                Manifest zolt.toml

                Project
                  name: standalone
                  version: 1.2.3
                  group: com.example
                  java: 21
                  license: Apache-2.0

                Toolchains
                  java: version 21, distribution temurin, policy require-managed

                Versions
                  jackson: 2.19.0

                Platforms
                  com.fasterxml.jackson:jackson-bom: versionRef jackson

                Coverage
                  line: 88""", result.stdout().stripTrailing());
        assertEquals("", result.stderr());
    }

    @Test
    void manifestViewNeverMaterializesWorkspaceInheritance() throws IOException {
        Path member = writeWorkspace();

        CommandResult result = execute(
                "config", "show", "--manifest", "--directory", member.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("name: api"), result.stdout());
        // group, version, and java live in [workspace.project]; the authored view must not expand them.
        assertFalse(result.stdout().contains("group:"), result.stdout());
        assertFalse(result.stdout().contains("version:"), result.stdout());
        assertFalse(result.stdout().contains("java:"), result.stdout());
    }

    @Test
    void effectiveViewNamesTheOriginOfEveryValue() throws IOException {
        Path member = writeWorkspace();

        CommandResult result = execute(
                "config", "show", "--effective", "--directory", member.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Effective project api"), result.stdout());
        assertTrue(result.stdout().contains("manifest: apps/api/zolt.toml"), result.stdout());
        assertTrue(result.stdout().contains("workspace: platform"), result.stdout());
        assertTrue(result.stdout().contains("member: apps/api"), result.stdout());
        assertTrue(result.stdout().contains("selection: implicit-all"), result.stdout());
        assertTrue(
                result.stdout().contains("name: api (authored: apps/api/zolt.toml project.name)"),
                result.stdout());
        assertTrue(
                result.stdout().contains("group: com.example (inherited: zolt.toml workspace.project.group)"),
                result.stdout());
        assertTrue(
                result.stdout().contains("version: 4.5.6 (inherited: zolt.toml workspace.project.version)"),
                result.stdout());
        assertTrue(result.stdout().contains("java: 21 (inherited: zolt.toml"), result.stdout());
        assertTrue(result.stdout().contains("line: 88 (inherited: zolt.toml"), result.stdout());
        assertTrue(
                result.stdout().contains("central: https://repo.maven.apache.org/maven2 (built-in)"),
                result.stdout());
    }

    @Test
    void effectiveViewReportsAnExplicitWorkspaceSelection() throws IOException {
        Path member = writeWorkspace("""

                [workspace.members]
                default = ["apps/api"]
                include = ["apps/*"]
                """);

        CommandResult result = execute(
                "config", "show", "--effective", "--directory", member.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("selection: explicit-default"), result.stdout());
    }

    @Test
    void effectiveViewAtAVirtualWorkspaceRootReportsTheWorkspaceItself() throws IOException {
        writeWorkspace();

        CommandResult result = execute(
                "config", "show", "--effective", "--directory", tempDir.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Effective workspace platform"), result.stdout());
        assertTrue(result.stdout().contains("selection: implicit-all"), result.stdout());
        assertTrue(result.stdout().contains("selected: apps/api"), result.stdout());
        // A virtual root has no project to compose, so every shared value is authored right here.
        assertTrue(result.stdout().contains("shared values: authored by this workspace root"),
                result.stdout());
        assertTrue(result.stdout().contains("group: com.example"), result.stdout());
        assertTrue(result.stdout().contains("line: 88"), result.stdout());
    }

    @Test
    void neitherViewReadsUserGlobalConfiguration() throws IOException {
        writeStandalone();

        CommandResult manifest = execute(
                "config", "show", "--manifest", "--directory", tempDir.toString());
        CommandResult effective = execute(
                "config", "show", "--effective", "--directory", tempDir.toString());

        assertFalse(manifest.stdout().contains("User global config"), manifest.stdout());
        assertFalse(effective.stdout().contains("User global config"), effective.stdout());
        assertFalse(manifest.stdout().contains("cache.root"), manifest.stdout());
        assertFalse(effective.stdout().contains("cache.root"), effective.stdout());
    }

    @Test
    void reportsAMissingManifestAsAUserError() {
        CommandResult result = execute(
                "config", "show", "--manifest", "--directory", tempDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Could not find zolt.toml"), result.stderr());
    }

    private void writeStandalone() throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), """
                [project]
                name = "standalone"
                version = "1.2.3"
                group = "com.example"
                java = 21
                license = "Apache-2.0"

                [toolchain.java]
                version = 21
                distribution = "temurin"
                policy = "require-managed"

                [versions]
                jackson = "2.19.0"

                [platforms]
                "com.fasterxml.jackson:jackson-bom" = { versionRef = "jackson" }

                [coverage]
                line = 88
                """);
    }

    private Path writeWorkspace() throws IOException {
        return writeWorkspace("""

                [workspace.members]
                include = ["apps/*"]
                """);
    }

    private Path writeWorkspace(String members) throws IOException {
        Path member = tempDir.resolve("apps/api");
        Files.createDirectories(member);
        Files.writeString(tempDir.resolve("zolt.toml"), """
                [workspace]
                name = "platform"
                """ + members + """

                [workspace.project]
                group = "com.example"
                version = "4.5.6"
                java = 21

                [coverage]
                line = 88
                """);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "api"
                """);
        return member;
    }
}
