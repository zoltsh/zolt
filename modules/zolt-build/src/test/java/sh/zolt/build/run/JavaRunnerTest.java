package sh.zolt.build.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.JavaRunException;
import sh.zolt.cancel.BuildCancellation;
import sh.zolt.classpath.Classpath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class JavaRunnerTest {
    @Test
    void passesRuntimeClasspathMainClassAndArguments() {
        List<List<String>> commands = new ArrayList<>();
        JavaRunner runner = new JavaRunner(":", (command, outputConsumer) -> {
            commands.add(command);
            return new JavaRunner.ProcessResult(0, "hello\n");
        });

        JavaRunResult result = runner.run(
                Path.of("java"),
                new Classpath(List.of(Path.of("target/classes"), Path.of("lib.jar"))),
                "com.example.Main",
                List.of("one", "two"));

        assertEquals("hello\n", result.output());
        assertEquals(List.of(
                "java",
                "-classpath",
                "target/classes:lib.jar",
                "com.example.Main",
                "one",
                "two"), commands.getFirst());
    }

    @Test
    void placesJvmArgumentsBeforeClasspathAndMainClass() {
        List<List<String>> commands = new ArrayList<>();
        JavaRunner runner = new JavaRunner(":", (command, outputConsumer) -> {
            commands.add(command);
            return new JavaRunner.ProcessResult(0, "hello\n");
        });

        runner.run(
                Path.of("java"),
                new Classpath(List.of(Path.of("target/classes"))),
                "com.example.Main",
                List.of("-Ddemo=true"),
                List.of("run"));

        assertEquals(List.of(
                "java",
                "-Ddemo=true",
                "-classpath",
                "target/classes",
                "com.example.Main",
                "run"), commands.getFirst());
    }

    @Test
    void passesEnvironmentToProcessRunner() {
        List<Map<String, String>> environments = new ArrayList<>();
        JavaRunner runner = new JavaRunner(":", new JavaRunner.ProcessRunner() {
            @Override
            public JavaRunner.ProcessResult run(List<String> command, java.util.function.Consumer<String> outputConsumer) {
                throw new AssertionError("Environment-aware runner should be used.");
            }

            @Override
            public JavaRunner.ProcessResult run(
                    List<String> command,
                    Map<String, String> environment,
                    java.util.function.Consumer<String> outputConsumer) {
                environments.add(environment);
                return new JavaRunner.ProcessResult(0, "hello\n");
            }
        });

        runner.run(
                Path.of("java"),
                new Classpath(List.of(Path.of("target/classes"))),
                "com.example.Main",
                List.of("-Ddemo=true"),
                List.of(),
                Map.of("TZ", "America/Chicago"));

        assertEquals(Map.of("TZ", "America/Chicago"), environments.getFirst());
    }

    @Test
    void streamsOutputAndStillReturnsCapturedOutput() {
        List<String> streamed = new ArrayList<>();
        JavaRunner runner = new JavaRunner(":", (command, outputConsumer) -> {
            outputConsumer.accept("starting\n");
            outputConsumer.accept("ready\n");
            return new JavaRunner.ProcessResult(0, "starting\nready\n");
        });

        JavaRunResult result = runner.run(
                Path.of("java"),
                new Classpath(List.of(Path.of("target/classes"))),
                "com.example.Main",
                List.of(),
                streamed::add);

        assertEquals(List.of("starting\n", "ready\n"), streamed);
        assertEquals("starting\nready\n", result.output());
    }

    @Test
    void runsJarWithArguments() {
        List<List<String>> commands = new ArrayList<>();
        JavaRunner runner = new JavaRunner(":", (command, outputConsumer) -> {
            commands.add(command);
            return new JavaRunner.ProcessResult(0, "boot\n");
        });

        JavaRunResult result = runner.runJar(
                Path.of("java"),
                Path.of("target/demo.jar"),
                "com.example.Main",
                List.of("one", "two"));

        assertEquals("com.example.Main", result.mainClass());
        assertEquals("boot\n", result.output());
        assertEquals(List.of("java", "-jar", "target/demo.jar", "one", "two"), commands.getFirst());
    }

    @Test
    void nonZeroExitIncludesApplicationOutput() {
        JavaRunner runner = new JavaRunner(":", (command, outputConsumer) -> new JavaRunner.ProcessResult(7, "boom\n"));

        JavaRunException exception = assertThrows(
                JavaRunException.class,
                () -> runner.run(
                        Path.of("java"),
                        new Classpath(List.of(Path.of("target/classes"))),
                        "com.example.Main",
                        List.of()));

        assertTrue(exception.getMessage().contains("java exited with code 7"));
        assertTrue(exception.getMessage().contains("boom"));
    }

    @Test
    void sigtermExitReturnsCleanStopWithSignal() {
        JavaRunner runner = new JavaRunner(":", (command, outputConsumer) -> new JavaRunner.ProcessResult(143, "started\n"));

        JavaRunResult result = runner.run(
                Path.of("java"),
                new Classpath(List.of(Path.of("target/classes"))),
                "com.example.Main",
                List.of());

        assertTrue(result.signalled());
        assertEquals(15, result.signal());
        assertEquals("com.example.Main", result.mainClass());
        assertEquals("started\n", result.output());
    }

    @Test
    void sigintExitReturnsCleanStopWithSignal() {
        JavaRunner runner = new JavaRunner(":", (command, outputConsumer) -> new JavaRunner.ProcessResult(130, "started\n"));

        JavaRunResult result = runner.run(
                Path.of("java"),
                new Classpath(List.of(Path.of("target/classes"))),
                "com.example.Main",
                List.of());

        assertTrue(result.signalled());
        assertEquals(2, result.signal());
    }

    @Test
    void genuineNonZeroExitStillThrows() {
        JavaRunner runner = new JavaRunner(":", (command, outputConsumer) -> new JavaRunner.ProcessResult(1, "boom\n"));

        JavaRunException exception = assertThrows(
                JavaRunException.class,
                () -> runner.run(
                        Path.of("java"),
                        new Classpath(List.of(Path.of("target/classes"))),
                        "com.example.Main",
                        List.of()));

        assertTrue(exception.getMessage().contains("java exited with code 1"));
    }

    @Test
    void zeroExitIsNotSignalled() {
        JavaRunner runner = new JavaRunner(":", (command, outputConsumer) -> new JavaRunner.ProcessResult(0, "done\n"));

        JavaRunResult result = runner.run(
                Path.of("java"),
                new Classpath(List.of(Path.of("target/classes"))),
                "com.example.Main",
                List.of());

        assertFalse(result.signalled());
    }

    @Test
    void cancellationTerminatesJavaProcessAndDescendants() throws Exception {
        BuildCancellation cancellation = new BuildCancellation();
        CountDownLatch ready = new CountDownLatch(1);
        StringBuilder output = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        String processClasspath = Path.of(
                        BlockingJavaProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                cancellation.call(() -> {
                    new JavaRunner().run(
                            javaExecutable(),
                            new Classpath(List.of(Path.of(processClasspath))),
                            BlockingJavaProcess.class.getName(),
                            List.of(javaExecutable().toString(), processClasspath),
                            chunk -> {
                                synchronized (output) {
                                    output.append(chunk);
                                    if (output.indexOf("\n") >= 0) {
                                        ready.countDown();
                                    }
                                }
                            });
                    return null;
                });
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        assertTrue(ready.await(5, TimeUnit.SECONDS), "Java child did not become ready.");
        long[] processIds;
        synchronized (output) {
            String[] values = output.toString().strip().split(",", -1);
            processIds = new long[] {
                    Long.parseLong(values[0]),
                    Long.parseLong(values[1])
            };
        }

        cancellation.cancel();
        caller.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(caller.isAlive(), "JavaRunner remained blocked after cancellation.");
        for (long processId : processIds) {
            assertTrue(
                    ProcessHandle.of(processId)
                            .map(process -> !process.isAlive())
                            .orElse(true),
                    "Cancelled Java process is still alive: " + processId);
        }
        if (failure.get() != null) {
            assertTrue(
                    failure.get() instanceof JavaRunException,
                    "Unexpected cancellation failure: " + failure.get());
        }
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name", "")
                        .toLowerCase()
                        .contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    public static final class BlockingJavaProcess {
        private BlockingJavaProcess() {
        }

        public static void main(String[] arguments) throws Exception {
            Process descendant = new ProcessBuilder(
                    arguments[0],
                    "-classpath",
                    arguments[1],
                    BlockingDescendant.class.getName())
                    .start();
            System.out.println(ProcessHandle.current().pid() + "," + descendant.pid());
            System.out.flush();
            new CountDownLatch(1).await();
        }
    }

    public static final class BlockingDescendant {
        private BlockingDescendant() {
        }

        public static void main(String[] arguments) throws InterruptedException {
            new CountDownLatch(1).await();
        }
    }
}
