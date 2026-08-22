package sh.zolt.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Design §11.1 and §4.5: the {@code [toolchain.zolt]} pin governing a directory is the nearest one
 * at or above it, so a workspace root's authoritative pin is found from any member directory and a
 * nearer manifest's pin wins. The search reads authored manifests only — a pin is a property of the
 * file, not of a composed project.
 */
final class ZoltVersionPinReaderTest {
    private final ZoltVersionPinReader reader = new ZoltVersionPinReader();

    @TempDir
    private Path tempDir;

    @Test
    void nestedMemberDirectoryFindsTheRootPinAndTheManifestThatDeclaredIt() throws IOException {
        Path root = tempDir.resolve("workspace");
        Path member = root.resolve("apps/api/src/main/java");
        Files.createDirectories(member);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]

                [toolchain.zolt]
                version = "0.4.2"
                """);
        Files.writeString(root.resolve("apps/api/zolt.toml"), """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);

        ZoltVersionRequirement requirement = reader.find(member).orElseThrow();

        assertEquals("0.4.2", requirement.zoltVersion());
        assertEquals(root.resolve("zolt.toml"), requirement.manifestPath());
    }

    @Test
    void aNearerPinOverridesTheRootPin() throws IOException {
        Path root = tempDir.resolve("override");
        Path project = root.resolve("nested");
        Files.createDirectories(project);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["nested"]

                [toolchain.zolt]
                version = "0.4.2"
                """);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "nested"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [toolchain.zolt]
                version = "0.9.1"
                """);

        ZoltVersionRequirement requirement = reader.find(project).orElseThrow();

        assertEquals("0.9.1", requirement.zoltVersion());
        assertEquals(project.resolve("zolt.toml"), requirement.manifestPath());
    }

    @Test
    void manifestsWithoutAPinAreSkippedAndAnUnpinnedTreeReportsNothing() throws IOException {
        Path root = tempDir.resolve("unpinned");
        Path project = root.resolve("nested");
        Files.createDirectories(project);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["nested"]
                """);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "nested"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);

        assertTrue(reader.find(project).isEmpty());
        assertTrue(reader.read(project.resolve("zolt.toml")).isEmpty());
    }

    @Test
    void aManifestFileIsAcceptedInPlaceOfItsDirectory() throws IOException {
        Path project = tempDir.resolve("file-start");
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "solo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [toolchain.zolt]
                version = "1.0.0"
                """);

        assertEquals(
                "1.0.0",
                reader.find(project.resolve("zolt.toml")).orElseThrow().zoltVersion());
    }
}
