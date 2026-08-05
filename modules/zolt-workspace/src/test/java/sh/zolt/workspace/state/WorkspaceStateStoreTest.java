package sh.zolt.workspace.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceStateStoreTest {
    @TempDir
    private Path tempDir;

    @Test
    void concurrentWritesUseUniqueTemporaryFiles() throws Exception {
        WorkspaceStateStore store = new WorkspaceStateStore();
        WorkspaceState first =
                new WorkspaceState(Map.of("apps/api", memberState("first")));
        WorkspaceState second =
                new WorkspaceState(Map.of("apps/api", memberState("second")));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<?>> writes = new ArrayList<>();
        try {
            for (int index = 0; index < 100; index++) {
                WorkspaceState state = index % 2 == 0 ? first : second;
                writes.add(executor.submit(() -> {
                    start.await();
                    store.write(tempDir, state);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> write : writes) {
                write.get();
            }
        } finally {
            executor.shutdownNow();
        }

        WorkspaceState written = store.read(tempDir);
        assertTrue(written.members().equals(first.members())
                || written.members().equals(second.members()));
        try (var paths = Files.list(tempDir.resolve(".zolt"))) {
            assertTrue(paths.noneMatch(path -> path.getFileName()
                    .toString()
                    .endsWith(".tmp")));
        }
    }

    /**
     * The fence only means anything while the state file's timestamp stays behind the inputs it was
     * written after. A command that concluded nothing changed must therefore leave the file untouched
     * rather than rewrite identical bytes and drag the fence forward over every input edited since.
     */
    @Test
    void anUnchangedStateIsNotRewritten() throws Exception {
        WorkspaceStateStore store = new WorkspaceStateStore();
        WorkspaceState state = new WorkspaceState(Map.of("apps/api", memberState("first")));
        assertTrue(store.write(tempDir, state));
        FileTime committed = Files.getLastModifiedTime(store.path(tempDir));

        assertFalse(store.write(tempDir, state));

        assertEquals(committed, Files.getLastModifiedTime(store.path(tempDir)));
        assertTrue(store.write(tempDir, new WorkspaceState(Map.of("apps/api", memberState("second")))));
    }

    private static WorkspaceMemberState memberState(String value) {
        return new WorkspaceMemberState(
                "config-" + value,
                "toolchain-" + value,
                "source-" + value,
                "resource-" + value,
                "generated-" + value,
                "compile-" + value,
                "output-" + value,
                "public-" + value,
                "package-" + value,
                "test-" + value,
                "test-resource-" + value,
                "test-output-" + value,
                "processor-" + value,
                "generated-output-" + value);
    }
}
