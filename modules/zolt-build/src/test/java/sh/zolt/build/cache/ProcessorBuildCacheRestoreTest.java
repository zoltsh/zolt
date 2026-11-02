package sh.zolt.build.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.classpath.Classpath;

final class ProcessorBuildCacheRestoreTest {
    @TempDir
    private Path tempDir;

    @Test
    void createsGeneratedSourceRootForProcessorCacheHit() {
        Path generated = tempDir.resolve("generated/sources");
        BuildCacheRestoreResult restored = BuildCacheRestoreResult.restoredFrom("local", 3);

        BuildCacheRestoreResult completed = ProcessorBuildCacheRestore.complete(
                restored,
                new Classpath(List.of(tempDir.resolve("processor.jar"))),
                generated);

        assertEquals(restored, completed);
        assertTrue(Files.isDirectory(generated));
    }

    @Test
    void leavesNonProcessorRestoreLayoutAlone() {
        Path generated = tempDir.resolve("generated/sources");
        BuildCacheRestoreResult restored = BuildCacheRestoreResult.restoredFrom("remote", 2);

        BuildCacheRestoreResult completed = ProcessorBuildCacheRestore.complete(
                restored,
                new Classpath(List.of()),
                generated);

        assertEquals(restored, completed);
        assertFalse(Files.exists(generated));
    }

    @Test
    void degradesIncompleteRestoreToCacheMiss() throws Exception {
        Path blockingFile = Files.writeString(tempDir.resolve("generated"), "not a directory");
        BuildCacheRestoreResult restored = BuildCacheRestoreResult.restoredFrom("local", 1);

        BuildCacheRestoreResult completed = ProcessorBuildCacheRestore.complete(
                restored,
                new Classpath(List.of(tempDir.resolve("processor.jar"))),
                blockingFile.resolve("sources"));

        assertFalse(completed.restored());
        assertEquals(0, completed.classCount());
    }
}
