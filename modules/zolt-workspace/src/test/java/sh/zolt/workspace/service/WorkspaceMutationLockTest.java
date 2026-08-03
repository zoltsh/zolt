package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

final class WorkspaceMutationLockTest {
    @TempDir
    private Path tempDir;

    @Test
    void serializesMutationAcrossProcesses() throws Exception {
        Process process;
        try (WorkspaceMutationLock ignored =
                WorkspaceMutationLock.acquire(tempDir)) {
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
        try (WorkspaceMutationLock outer =
                WorkspaceMutationLock.acquire(tempDir)) {
            try (WorkspaceMutationLock ignored =
                    WorkspaceMutationLock.acquire(tempDir)) {
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

    private Process startProbe() throws IOException, URISyntaxException {
        Path java = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java");
        return new ProcessBuilder(
                        java.toString(),
                        "-cp",
                        classLocation(WorkspaceMutationLockTest.class),
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
                    .resolve("workspace-mutation.lock");
            try (FileChannel channel = FileChannel.open(
                            lockPath,
                            StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                // Acquiring the lock is the probe.
            }
        }
    }
}
