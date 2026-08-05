package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
        assertTrue(written.equals(first) || written.equals(second));
        try (var paths = Files.list(tempDir.resolve(".zolt"))) {
            assertTrue(paths.noneMatch(path -> path.getFileName()
                    .toString()
                    .endsWith(".tmp")));
        }
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
                "package-key-" + value);
    }
}
