package sh.zolt.build.nativeimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import sh.zolt.build.NativeImageException;
import sh.zolt.cancel.BuildCancellation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeImageProcessLifecycleTest {
    @TempDir
    private Path tempDir;

    @Test
    void successfulParentDoesNotHangOnDescendantHoldingStdout() throws Exception {
        assumeFalse(isWindows(), "POSIX process fixture requires /bin/sh");
        Path childPid = tempDir.resolve("retained-pipe-child.pid");
        Path script = script("retained-pipe.sh", """
                pid_file="$1"
                shift
                last=""
                for argument in "$@"; do last="$argument"; done
                (trap '' HUP TERM; sleep 30) &
                echo $! > "$pid_file"
                printf '#!/bin/sh\nexit 0\n' > "$last"
                chmod +x "$last"
                printf 'native ok\n'
                exit 0
                """);
        Instant started = Instant.now();
        try {
            NativeImageResult result = new NativeImageRunner().build(request(
                    tempDir.resolve("target/native/demo"),
                    List.of(script.toString(), childPid.toString())));

            assertTrue(Duration.between(started, Instant.now()).compareTo(Duration.ofSeconds(5)) < 0);
            assertEquals("native ok\n", result.output());
            assertTrue(Files.isExecutable(result.outputBinary()));
        } finally {
            stopPid(childPid);
        }
    }

    @Test
    void cancellationKillsParentAndTermIgnoringDescendantAndPreservesBinary() throws Exception {
        assumeFalse(isWindows(), "POSIX process fixture requires /bin/sh");
        Path parentPid = tempDir.resolve("cancel-parent.pid");
        Path childPid = tempDir.resolve("cancel-child.pid");
        Path script = script("cancel-tree.sh", """
                parent_pid="$1"
                child_pid="$2"
                echo $$ > "$parent_pid"
                (trap '' TERM HUP; while :; do sleep 1; done) &
                echo $! > "$child_pid"
                printf 'READY\n'
                wait
                """);
        Path output = tempDir.resolve("target/native/demo");
        Files.createDirectories(output.getParent());
        Files.writeString(output, "last-known-good");
        output.toFile().setExecutable(true, false);
        BuildCancellation cancellation = new BuildCancellation();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> cancellation.call(() -> {
            try {
                new NativeImageRunner().build(request(
                        output,
                        List.of(script.toString(), parentPid.toString(), childPid.toString())));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
            return null;
        }));

        awaitFile(parentPid);
        awaitFile(childPid);
        awaitLogText(output.resolveSibling("native-image.log"), "READY");
        cancellation.cancel();
        caller.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(caller.isAlive(), "Native runner remained blocked after cancellation.");
        assertNotNull(failure.get());
        assertTrue(failure.get() instanceof NativeImageException, failure.get().toString());
        assertTrue(failure.get().getMessage().contains("cancelled"), failure.get().getMessage());
        assertEquals("last-known-good", Files.readString(output));
        assertStopped(parentPid);
        assertStopped(childPid);
        assertNoStagingFiles(output);
    }

    @Test
    void interruptionKillsParentAndDescendantAndRemovesStaging() throws Exception {
        assumeFalse(isWindows(), "POSIX process fixture requires /bin/sh");
        Path parentPid = tempDir.resolve("interrupt-parent.pid");
        Path childPid = tempDir.resolve("interrupt-child.pid");
        Path script = script("interrupt-tree.sh", """
                parent_pid="$1"
                child_pid="$2"
                echo $$ > "$parent_pid"
                (trap '' TERM HUP; while :; do sleep 1; done) &
                echo $! > "$child_pid"
                printf 'READY\n'
                wait
                """);
        Path output = tempDir.resolve("target/native/demo");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                new NativeImageRunner().build(request(
                        output,
                        List.of(script.toString(), parentPid.toString(), childPid.toString())));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        awaitFile(parentPid);
        awaitFile(childPid);
        awaitLogText(output.resolveSibling("native-image.log"), "READY");
        caller.interrupt();
        caller.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(caller.isAlive(), "Native runner remained blocked after interruption.");
        assertNotNull(failure.get());
        assertTrue(failure.get() instanceof NativeImageException, failure.get().toString());
        assertTrue(failure.get().getMessage().contains("interrupted"), failure.get().getMessage());
        assertFalse(Files.exists(output));
        assertStopped(parentPid);
        assertStopped(childPid);
        assertNoStagingFiles(output);
    }

    @Test
    void highVolumeOutputIsStreamedToLogWithBoundedDiagnosticTail() throws Exception {
        assumeFalse(isWindows(), "POSIX process fixture requires /bin/sh");
        Path script = script("noisy.sh", """
                last=""
                for argument in "$@"; do last="$argument"; done
                index=0
                while [ "$index" -lt 12000 ]; do
                  printf 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-output\n'
                  index=$((index + 1))
                done
                printf '#!/bin/sh\nexit 0\n' > "$last"
                chmod +x "$last"
                """);
        Path output = tempDir.resolve("target/native/demo");

        NativeImageResult result = new NativeImageRunner().build(request(output, List.of(script.toString())));

        assertTrue(result.output().length() <= 64 * 1024, Integer.toString(result.output().length()));
        assertTrue(Files.size(result.logFile()) > 500_000L, Long.toString(Files.size(result.logFile())));
        assertTrue(Files.isExecutable(output));
    }

    private NativeImageRequest request(Path output, List<String> arguments) {
        return new NativeImageRequest(
                Path.of("/bin/sh"),
                Path.of("target/demo.jar"),
                List.of(),
                "com.example.Main",
                output,
                output.resolveSibling("native-image.log"),
                arguments);
    }

    private Path script(String name, String content) throws IOException {
        Path script = tempDir.resolve(name);
        Files.writeString(script, content);
        return script;
    }

    private static void awaitFile(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Files.notExists(path) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(Files.isRegularFile(path), "Process did not write " + path);
    }

    private static void awaitLogText(Path log, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(log) && Files.readString(log).contains(expected)) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Native log did not contain " + expected);
    }

    private static void assertStopped(Path pidFile) throws Exception {
        long pid = Long.parseLong(Files.readString(pidFile).strip());
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle != null && handle.isAlive()) {
            handle.onExit().get(5, TimeUnit.SECONDS);
        }
        assertTrue(ProcessHandle.of(pid).map(process -> !process.isAlive()).orElse(true));
    }

    private static void stopPid(Path pidFile) throws Exception {
        if (Files.notExists(pidFile)) {
            return;
        }
        long pid = Long.parseLong(Files.readString(pidFile).strip());
        ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
        ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(handle -> {
            try {
                handle.onExit().get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Best-effort cleanup for the retained-pipe fixture.
            }
        });
    }

    private static void assertNoStagingFiles(Path output) throws IOException {
        try (var paths = Files.list(output.getParent())) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().contains(".zolt-staging-")));
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
