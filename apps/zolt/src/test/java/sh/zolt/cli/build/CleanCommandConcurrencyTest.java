package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.workspace.service.WorkspaceMutationLock;

final class CleanCommandConcurrencyTest {
    @TempDir
    private Path tempDir;

    @Test
    void memberCleanWaitsForWorkspaceBuildAndPreservesOtherMembers() throws Exception {
        Files.writeString(tempDir.resolve("zolt.toml"), """
                [workspace]
                name = "concurrent-clean"
                [workspace.members]
                include = ["app", "lib"]
                """);
        for (String member : new String[] {"app", "lib"}) {
            Path directory = tempDir.resolve(member);
            Files.createDirectories(directory.resolve("target/classes"));
            Files.writeString(directory.resolve("target/classes/Output.class"), "compiled");
            Files.writeString(directory.resolve("zolt.toml"), """
                    [project]
                    name = "%s"
                    version = "1.0.0"
                    group = "example"
                    java = %s
                    """.formatted(member, Runtime.version().feature()));
        }
        CountDownLatch started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<CommandResult> clean;
            try (WorkspaceMutationLock ignored = WorkspaceMutationLock.acquire(tempDir)) {
                clean = executor.submit(() -> {
                    started.countDown();
                    return execute("clean", "--cwd", tempDir.resolve("app").toString());
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> clean.get(1, TimeUnit.SECONDS));
                assertTrue(Files.exists(tempDir.resolve("app/target/classes/Output.class")));
            }
            CommandResult result = clean.get(10, TimeUnit.SECONDS);
            assertEquals(0, result.exitCode(), result.stderr());
            assertFalse(Files.exists(tempDir.resolve("app/target")));
            assertTrue(Files.exists(tempDir.resolve("lib/target/classes/Output.class")));
        }
    }
}
