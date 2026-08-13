package sh.zolt.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalArtifactCacheContainmentTest {
    @TempDir
    private Path tempDir;

    @Test
    void downloadStagingCannotEscapeThroughSymlink() throws Exception {
        Path cacheRoot = Files.createDirectory(tempDir.resolve("staging-cache"));
        Path outside = Files.createDirectory(tempDir.resolve("staging-outside"));
        try {
            Files.createSymbolicLink(cacheRoot.resolve(".downloads"), outside);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }

        ArtifactCacheException exception = assertThrows(
                ArtifactCacheException.class,
                () -> new LocalArtifactCache(cacheRoot));

        assertTrue(exception.getMessage().contains("download staging path"));
        try (var files = Files.list(outside)) {
            assertEquals(List.of(), files.toList());
        }
    }
}
