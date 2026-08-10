package sh.zolt.cli.dependency;

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

/** Every public manifest mutation command shares the same source-preservation contract. */
final class ManifestMutationPreservationTest {
    @TempDir
    private Path tempDir;

    @Test
    void addPreservesUnrelatedManifestSource() throws IOException {
        assertPreserved(
                "add",
                "[dependencies]\n",
                "\"com.example:added\" = \"2.0.0\"",
                "add", "--no-resolve", "com.example:added:2.0.0");
    }

    @Test
    void removePreservesUnrelatedManifestSource() throws IOException {
        assertPreserved(
                "remove",
                "[dependencies]\n\"com.example:old\" = \"1.0.0\"\n",
                null,
                "remove", "com.example:old");
    }

    @Test
    void platformAddPreservesUnrelatedManifestSource() throws IOException {
        assertPreserved(
                "platform-add",
                "[platforms]\n",
                "\"com.example:platform\" = \"1.0.0\"",
                "platform", "add", "--no-resolve", "com.example:platform:1.0.0");
    }

    @Test
    void platformRemovePreservesUnrelatedManifestSource() throws IOException {
        assertPreserved(
                "platform-remove",
                "[platforms]\n\"com.example:platform\" = \"1.0.0\"\n",
                null,
                "platform", "remove", "com.example:platform");
    }

    @Test
    void versionSetPreservesUnrelatedManifestSource() throws IOException {
        assertPreserved(
                "version-set",
                "[versions]\nexisting = \"1.0.0\"\n",
                "\"added\" = \"2.0.0\"",
                "version", "set", "--no-resolve", "added", "2.0.0");
    }

    @Test
    void versionRemovePreservesUnrelatedManifestSource() throws IOException {
        assertPreserved(
                "version-remove",
                "[versions]\nold = \"1.0.0\"\nkeep = \"2.0.0\"\n",
                "keep = \"2.0.0\"",
                "version", "remove", "--no-resolve", "old");
    }

    private void assertPreserved(
            String name,
            String mutableSection,
            String expectedMutation,
            String... command) throws IOException {
        Path project = tempDir.resolve(name);
        Files.createDirectories(project);
        Path manifest = project.resolve("zolt.toml");
        String original = manifest(mutableSection);
        Files.writeString(manifest, original);
        String[] arguments = new String[command.length + 4];
        System.arraycopy(command, 0, arguments, 0, command.length);
        arguments[command.length] = "--cwd";
        arguments[command.length + 1] = project.toString();
        arguments[command.length + 2] = "--cache-root";
        arguments[command.length + 3] = tempDir.resolve(name + "-cache").toString();

        CommandResult result = execute(arguments);

        assertEquals(0, result.exitCode(), result.stderr());
        String edited = Files.readString(manifest);
        assertEquals(sentinel(original, "[coverage]"), sentinel(edited, "[coverage]"));
        assertEquals(sentinel(original, "[toolchain.java]"), sentinel(edited, "[toolchain.java]"));
        assertEquals(sentinel(original, "[publish.central]"), sentinel(edited, "[publish.central]"));
        assertEquals(sentinel(original, "[commands.tasks.\"verify.all\"]"),
                sentinel(edited, "[commands.tasks.\"verify.all\"]"));
        assertEquals(sentinel(original, "[workspace]"), sentinel(edited, "[workspace]"));
        assertTrue(edited.startsWith("# user-owned manifest\n[project]"));
        assertFalse(result.stdout().contains("may remove comments or formatting"));
        if (expectedMutation != null) {
            assertTrue(edited.contains(expectedMutation), edited);
        }
    }

    private static String manifest(String mutableSection) {
        return """
                # user-owned manifest
                [project]
                name = "preservation"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                %s
                [coverage]
                minLine = 88.0 # do not lower
                minBranch = 74.0

                [toolchain.java]
                version = "21"
                distribution = "graalvm-community"
                features = ["native-image"]
                policy = "require-managed"

                [publish.central]
                automatic = false

                [commands.tasks."verify.all"]
                command = ["zolt", "check"]
                environment = { "KEY.WITH.DOTS" = "a=b#c" }

                [workspace]
                name = "preservation"
                members = []
                """.formatted(mutableSection);
    }

    private static String sentinel(String source, String header) {
        int start = source.indexOf(header);
        int next = source.indexOf("\n[", start + header.length());
        return source.substring(start, next < 0 ? source.length() : next);
    }
}
