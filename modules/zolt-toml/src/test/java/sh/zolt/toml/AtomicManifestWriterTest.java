package sh.zolt.toml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Design §19.5: a manifest edit replaces the file atomically and only while the bytes it was planned
 * against are still on disk. A concurrent hand edit therefore wins — the command fails closed and the
 * human's version survives untouched.
 */
final class AtomicManifestWriterTest {
    private static final String ORIGINAL = """
            [project]
            name = "demo"
            version = "0.1.0"
            group = "com.example"
            java = 21
            """;

    @TempDir
    private Path tempDir;

    @Test
    void replacesTheManifestWhenTheCapturedBytesAreStillOnDisk() throws IOException {
        Path manifest = manifest(ORIGINAL);
        String edited = ORIGINAL + "\n[versions]\nguava = \"33.4.8-jre\"\n";

        AtomicManifestWriter.writePrepared(manifest, ORIGINAL, edited);

        assertEquals(edited, Files.readString(manifest));
        assertNoStagedSiblings();
    }

    @Test
    void concurrentManualEditIsNotOverwrittenAndTheCommandFailsClosed() throws IOException {
        Path manifest = manifest(ORIGINAL);
        String manual = ORIGINAL + "\n[versions]\nby-hand = \"1.0.0\"\n";
        Files.writeString(manifest, manual);

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> AtomicManifestWriter.writePrepared(
                        manifest, ORIGINAL, ORIGINAL + "\n[versions]\nby-zolt = \"2.0.0\"\n"));

        assertTrue(
                failure.getMessage().contains("changed while the edit was in progress"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("No changes were written"), failure.getMessage());
        assertEquals(manual, Files.readString(manifest));
        assertNoStagedSiblings();
    }

    @Test
    void anEditThatChangesNothingTouchesNoFile() throws IOException {
        Path manifest = manifest(ORIGINAL);

        AtomicManifestWriter.writePrepared(manifest, ORIGINAL, ORIGINAL);

        assertEquals(ORIGINAL, Files.readString(manifest));
        assertNoStagedSiblings();
    }

    private Path manifest(String content) throws IOException {
        Path manifest = tempDir.resolve("zolt.toml");
        Files.writeString(manifest, content);
        return manifest;
    }

    private void assertNoStagedSiblings() throws IOException {
        try (Stream<Path> entries = Files.list(tempDir)) {
            assertEquals(
                    java.util.List.of("zolt.toml"),
                    entries.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }
}
