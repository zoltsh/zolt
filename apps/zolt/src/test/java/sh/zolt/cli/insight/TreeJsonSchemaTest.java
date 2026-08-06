package sh.zolt.cli.insight;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.insight.TreeFixtures.golden;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The frozen machine contract of {@code zolt tree --format json}: standalone output stays schema 1
 * byte for byte, and {@code --workspace} emits schema 3 byte for byte. Both are compared against
 * committed goldens, because downstream decoders (the dependency-submission action) parse them
 * strictly and share one dependency-edge parser across the two versions.
 */
final class TreeJsonSchemaTest {
    @TempDir
    private Path tempDir;

    @Test
    void standaloneJsonStaysSchemaVersionOne() throws IOException {
        Path project = TreeFixtures.standaloneProject(tempDir.resolve("standalone"));

        CommandResult result = execute("tree", "--format", "json", "--cwd", project.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("", result.stderr());
        assertEquals(golden("tree-schema-v1.json"), result.stdout());
    }

    @Test
    void workspaceJsonEmitsSchemaVersionTwo() throws IOException {
        Path workspace = writeWorkspace();

        CommandResult result = execute("tree", "--workspace", "--format", "json", "--cwd", workspace.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("", result.stderr());
        assertEquals(golden("tree-workspace-schema-v3.json"), result.stdout());
    }

    @Test
    void workspaceJsonIsByteIdenticalAcrossRuns() throws IOException {
        Path workspace = writeWorkspace();

        CommandResult first = execute("tree", "--workspace", "--format", "json", "--cwd", workspace.toString());
        CommandResult second = execute("tree", "--workspace", "--format", "json", "--cwd", workspace.toString());

        assertEquals(0, first.exitCode(), first.stderr());
        assertEquals(first.stdout(), second.stdout());
    }

    /**
     * The two schemas spell an edge, a coordinate, and a variant the same way, so one consumer-side
     * parser reads either version.
     */
    @Test
    void bothSchemasShareTheEdgeAndVariantSpelling() throws IOException {
        Path project = TreeFixtures.standaloneProject(tempDir.resolve("shared-spelling"));

        CommandResult standalone = execute("tree", "--format", "json", "--cwd", project.toString());

        assertTrue(standalone.stdout().contains(
                "\"dependencies\": [\"com.example:lib:2.0.0:jar:compile\", "
                        + "\"org.example:agent:0.9.0:jar|runtime:compile\"]"),
                standalone.stdout());
        assertTrue(standalone.stdout().contains("\"coordinate\": \"org.example:agent:0.9.0:jar|runtime\""),
                standalone.stdout());
        assertTrue(standalone.stdout().contains("\"variant\": \"jar|runtime\""), standalone.stdout());

        String workspace = golden("tree-workspace-schema-v3.json");
        assertTrue(workspace.contains("\"dependencies\": [\"org.example:extra:2.0.0:jar:compile\"]"), workspace);
        assertTrue(workspace.contains("\"coordinate\": \"org.example:agent:0.9.0:jar|runtime\""), workspace);
        assertTrue(workspace.contains("\"variant\": \"jar|runtime\""), workspace);
    }

    private Path writeWorkspace() throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), TreeFixtures.WORKSPACE_CONFIG);
        return TreeFixtures.workspaceMembersAndLock(workspace);
    }
}
