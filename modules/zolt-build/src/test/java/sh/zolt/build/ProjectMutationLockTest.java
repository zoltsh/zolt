package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProjectMutationLockTest {
    @TempDir
    private Path tempDir;

    @Test
    void serializesMutationAcrossProcesses() throws Exception {
        Process process;
        try (ProjectMutationLock ignored =
                ProjectMutationLock.acquire(tempDir)) {
            process = startProbe();
            try (BufferedReader output = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                assertEquals("ready", output.readLine());
                assertThrows(
                        TimeoutException.class,
                        () -> process.onExit().get(
                                Duration.ofMillis(250).toMillis(),
                                TimeUnit.MILLISECONDS));
            }
        }
        try {
            assertEquals(0, process.waitFor());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void nestedLeaseKeepsProcessLockUntilOutermostClose() throws Exception {
        Process process;
        try (ProjectMutationLock outer =
                ProjectMutationLock.acquire(tempDir)) {
            try (ProjectMutationLock ignored =
                    ProjectMutationLock.acquire(tempDir)) {
                process = startProbe();
                try (BufferedReader output = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    assertEquals("ready", output.readLine());
                }
            }
            assertThrows(
                    TimeoutException.class,
                    () -> process.onExit().get(
                            Duration.ofMillis(250).toMillis(),
                            TimeUnit.MILLISECONDS));
        }
        try {
            assertEquals(0, process.waitFor());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void symbolicLinkAliasUsesTheSameReentrantLease() throws Exception {
        Path alias = tempDir.resolve("alias");
        try {
            Files.createSymbolicLink(alias, tempDir);
        } catch (IOException | UnsupportedOperationException exception) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links are unavailable: " + exception);
        }
        try (ProjectMutationLock outer = ProjectMutationLock.acquire(tempDir);
                ProjectMutationLock nested = ProjectMutationLock.acquire(alias)) {
            assertTrue(Files.isRegularFile(tempDir.resolve(".zolt/project-mutation.lock")));
        }
    }

    @Test
    void waitingThreadCanBeInterruptedWithoutReleasingAnotherOwner() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean acquired = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            started.countDown();
            try (ProjectMutationLock ignored = ProjectMutationLock.acquire(tempDir)) {
                acquired.set(true);
            } catch (BuildException exception) {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        try (ProjectMutationLock ignored = ProjectMutationLock.acquire(tempDir)) {
            waiter.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            waiter.interrupt();
            waiter.join(5000);
            assertFalse(waiter.isAlive());
            assertFalse(acquired.get());
            assertTrue(interrupted.get());
        } finally {
            waiter.interrupt();
            waiter.join(5000);
        }
        try (ProjectMutationLock ignored = ProjectMutationLock.acquire(tempDir)) {
            assertFalse(acquired.get());
        }
    }

    private Process startProbe() throws IOException, URISyntaxException {
        Path java = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java");
        return new ProcessBuilder(
                        java.toString(),
                        "-cp",
                        classLocation(ProjectMutationLockTest.class),
                        LockProbe.class.getName(),
                        tempDir.toString())
                .redirectErrorStream(true)
                .start();
    }

    private static String classLocation(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name")
                .toLowerCase()
                .contains("win");
    }

    public static final class LockProbe {
        private LockProbe() {
        }

        public static void main(String[] arguments) throws IOException {
            System.out.println("ready");
            System.out.flush();
            Path lockPath = Path.of(arguments[0])
                    .resolve(".zolt")
                    .resolve("project-mutation.lock");
            try (FileChannel channel = FileChannel.open(
                            lockPath,
                            StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                // Acquiring the lock is the probe.
            }
        }
    }
}
