package sh.zolt.lockfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CacheRelativePathTest {
    @TempDir
    private Path tempDir;

    @Test
    void acceptsPortableArtifactPaths() {
        CacheRelativePath path = new CacheRelativePath(
                "blobs/v2/sha256/abc123/demo-1.0.0.jar");

        assertEquals("blobs/v2/sha256/abc123/demo-1.0.0.jar", path.value());
    }

    @Test
    void rejectsNonRelativeAndNonPortablePaths() {
        List<String> invalid = List.of(
                "",
                "   ",
                "/tmp/demo.jar",
                "../demo.jar",
                "lib/../demo.jar",
                "./demo.jar",
                "lib/./demo.jar",
                "C:/cache/demo.jar",
                "C:\\cache\\demo.jar",
                "C:cache/demo.jar",
                "\\\\server\\share\\demo.jar",
                "lib\\demo.jar",
                "lib//demo.jar",
                "lib/");

        for (String candidate : invalid) {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new CacheRelativePath(candidate),
                    candidate);
            assertTrue(exception.getMessage().contains("Invalid cache-relative path"), candidate);
        }
    }

    @Test
    void resolvesContainedPath() throws IOException {
        Path cache = Files.createDirectories(tempDir.resolve("cache"));
        Path artifact = cache.resolve("blobs/demo.jar");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "jar");

        assertEquals(artifact, new CacheRelativePath("blobs/demo.jar").resolveWithin(cache));
    }

    @Test
    void rejectsExistingDirectorySymlinkEscape() throws IOException {
        Path cache = Files.createDirectories(tempDir.resolve("cache"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        createSymlink(cache.resolve("blobs"), outside);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new CacheRelativePath("blobs/demo.jar").resolveWithin(cache));

        assertTrue(exception.getMessage().contains("symbolic link"));
    }

    @Test
    void rejectsExistingFileSymlinkEscape() throws IOException {
        Path cache = Files.createDirectories(tempDir.resolve("cache/blobs"));
        Path outside = Files.writeString(tempDir.resolve("outside.jar"), "outside");
        createSymlink(cache.resolve("demo.jar"), outside);

        assertThrows(
                IllegalArgumentException.class,
                () -> new CacheRelativePath("blobs/demo.jar").resolveWithin(tempDir.resolve("cache")));
    }

    @Test
    void acceptsSymlinkWhoseTargetRemainsInCache() throws IOException {
        Path cache = Files.createDirectories(tempDir.resolve("cache"));
        Path stored = Files.createDirectories(cache.resolve("stored"));
        createSymlink(cache.resolve("blobs"), stored);

        assertEquals(
                cache.resolve("blobs/demo.jar"),
                new CacheRelativePath("blobs/demo.jar").resolveWithin(cache));
    }

    private static void createSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }
    }
}
