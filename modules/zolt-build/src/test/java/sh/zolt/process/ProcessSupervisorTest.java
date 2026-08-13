package sh.zolt.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import sh.zolt.cancel.BuildCancellation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProcessSupervisorTest {
    @TempDir
    private Path tempDir;

    @Test
    void streamsHighVolumeOutputWhileRetainingOnlyTheConfiguredTail() throws Exception {
        int emittedCharacters = 512 * 1024 + 1;
        AtomicInteger streamedCharacters = new AtomicInteger();

        SupervisedProcessResult result = new ProcessSupervisor().run(
                SupervisedProcessSpec.builder(javaCommand(NoisyProcess.class, "524288"))
                        .diagnosticTailCharacters(1024)
                        .stdoutConsumer(chunk -> streamedCharacters.addAndGet(chunk.length()))
                        .build());

        assertEquals(0, result.exitCode());
        assertEquals(emittedCharacters, streamedCharacters.get());
        assertEquals(1024, result.diagnosticTail().length());
        assertTrue(result.diagnosticTail().endsWith("\n"));
        assertTrue(result.endedWithNewline());
    }

    @Test
    void timeoutTerminatesParentChildAndGrandchildIncludingTermIgnoringChild() throws Exception {
        assumeFalse(isWindows(), "POSIX signal fixtures require /bin/sh");
        ProcessTreeFixture tree = writeProcessTreeFixture("timeout");

        SupervisedProcessResult result = new ProcessSupervisor().run(
                SupervisedProcessSpec.builder(tree.command())
                        .timeout(java.time.Duration.ofSeconds(1))
                        .build());

        assertTrue(result.timedOut());
        assertTrue(result.terminationInitiatedByZolt());
        tree.assertStopped();
    }

    @Test
    void buildCancellationTerminatesParentChildAndGrandchild() throws Exception {
        assumeFalse(isWindows(), "POSIX signal fixtures require /bin/sh");
        ProcessTreeFixture tree = writeProcessTreeFixture("cancel");
        BuildCancellation cancellation = new BuildCancellation();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<SupervisedProcessResult> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StringBuilder output = new StringBuilder();
        Thread caller = Thread.ofPlatform().start(() -> cancellation.call(() -> {
            try {
                result.set(new ProcessSupervisor().run(
                        SupervisedProcessSpec.builder(tree.command())
                                .stdoutConsumer(chunk -> recordOutput(chunk, output, ready))
                                .build()));
            } catch (IOException | InterruptedException exception) {
                failure.set(exception);
            }
            return null;
        }));

        assertTrue(ready.await(5, TimeUnit.SECONDS), "Process tree did not become ready.");
        cancellation.cancel();
        caller.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(caller.isAlive(), "Supervisor remained blocked after cancellation.");
        assertEquals(null, failure.get());
        assertNotNull(result.get());
        assertTrue(result.get().terminationInitiatedByZolt());
        tree.assertStopped();
    }

    @Test
    void inheritedInputSupportsInteractiveChildren() throws Exception {
        Process outer = new ProcessBuilder(javaCommand(InteractiveSupervisor.class, testClasspath()))
                .redirectErrorStream(true)
                .start();
        outer.getOutputStream().write("hello supervisor\n".getBytes(StandardCharsets.UTF_8));
        outer.getOutputStream().flush();
        outer.getOutputStream().close();

        String output = new String(outer.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(outer.waitFor(5, TimeUnit.SECONDS));
        assertEquals(0, outer.exitValue(), output);
        assertEquals("child:hello supervisor\n", output);
    }

    private ProcessTreeFixture writeProcessTreeFixture(String name) throws IOException {
        Path parent = tempDir.resolve(name + "-parent.pid");
        Path child = tempDir.resolve(name + "-child.pid");
        Path grandchild = tempDir.resolve(name + "-grandchild.pid");
        Path script = tempDir.resolve(name + "-tree.sh");
        Files.writeString(script, """
                echo $$ > "$1"
                (
                  trap '' TERM
                  sleep 30 &
                  echo $! > "$3"
                  wait
                ) &
                echo $! > "$2"
                while [ ! -s "$3" ]; do :; done
                echo READY
                wait
                """);
        return new ProcessTreeFixture(
                List.of("/bin/sh", script.toString(), parent.toString(), child.toString(), grandchild.toString()),
                List.of(parent, child, grandchild));
    }

    private static void recordOutput(String chunk, StringBuilder output, CountDownLatch ready) {
        synchronized (output) {
            output.append(chunk);
            if (output.indexOf("READY\n") >= 0) {
                ready.countDown();
            }
        }
    }

    private static List<String> javaCommand(Class<?> mainClass, String... arguments) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-classpath");
        command.add(testClasspath());
        command.add(mainClass.getName());
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }

    private static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
    }

    private static String testClasspath() {
        return System.getProperty("java.class.path");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static final class NoisyProcess {
        public static void main(String[] arguments) {
            int count = Integer.parseInt(arguments[0]);
            for (int index = 0; index < count; index++) {
                System.out.print('x');
            }
            System.out.println();
        }
    }

    public static final class InteractiveSupervisor {
        public static void main(String[] arguments) throws Exception {
            new ProcessSupervisor().run(
                    SupervisedProcessSpec.builder(javaCommand(StdinReader.class))
                            .inputPolicy(ProcessInputPolicy.INHERIT)
                            .stdoutConsumer(System.out::print)
                            .build());
        }
    }

    public static final class StdinReader {
        public static void main(String[] arguments) throws IOException {
            String input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            System.out.print("child:" + input);
        }
    }

    private record ProcessTreeFixture(List<String> command, List<Path> pidFiles) {
        void assertStopped() throws Exception {
            for (Path pidFile : pidFiles) {
                long pid = Long.parseLong(Files.readString(pidFile).strip());
                ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
                if (handle != null && handle.isAlive()) {
                    handle.onExit().get(5, TimeUnit.SECONDS);
                }
                assertTrue(
                        ProcessHandle.of(pid).map(process -> !process.isAlive()).orElse(true),
                        "Supervised descendant is still alive: " + pid);
            }
        }
    }
}
