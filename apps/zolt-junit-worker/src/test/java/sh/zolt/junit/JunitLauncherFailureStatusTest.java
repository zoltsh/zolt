package sh.zolt.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.error.WorkerFailureDiagnostic;
import sh.zolt.test.TestSelection;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JunitLauncherFailureStatusTest {
    @TempDir
    private Path tempDir;

    @Test
    void oneShotProcessReturnsFailureForAfterAllContainerFailure() throws Exception {
        Path source = tempDir.resolve("one-shot-src/LifecycleFailureTest.java");
        Path output = tempDir.resolve("one-shot-classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(output);
        Files.writeString(source, """
                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.Test;

                public class LifecycleFailureTest {
                    @Test
                    void passingTest() {
                    }

                    @AfterAll
                    static void brokenTeardown() {
                        throw new IllegalStateException("teardown failed");
                    }
                }
                """);
        Process compile = new ProcessBuilder(
                currentJavac().toString(),
                "-classpath",
                codeSource(org.junit.jupiter.api.Test.class).toString(),
                "-d",
                output.toString(),
                source.toString())
                .redirectErrorStream(true)
                .start();
        String compileOutput = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(compile.waitFor(30, TimeUnit.SECONDS), compileOutput);
        assertEquals(0, compile.exitValue(), compileOutput);

        List<Path> classpath = new ArrayList<>(compiledWorkerClasspath());
        classpath.add(output);
        Process worker = new ProcessBuilder(
                javaExecutable().toString(),
                "-classpath",
                classpath.stream()
                        .map(Path::toString)
                        .collect(Collectors.joining(java.io.File.pathSeparator)),
                JunitLauncherWorker.MAIN_CLASS,
                output.toString())
                .redirectErrorStream(true)
                .start();
        String workerOutput = new String(worker.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(worker.waitFor(30, TimeUnit.SECONDS), workerOutput);
        assertEquals(1, worker.exitValue(), workerOutput);
        assertTrue(workerOutput.contains("Tests succeeded: 1"), workerOutput);
        assertTrue(workerOutput.contains("Tests failed: 0"), workerOutput);
        assertTrue(workerOutput.contains("teardown failed"), workerOutput);
    }

    @Test
    void serverModeReturnsFailureForBeforeAllContainerFailure() {
        assertContainerFailure("sh.zolt.junit.BeforeAllFailureFixture", "setup failed");
    }

    @Test
    void serverModeReturnsFailureForAfterAllContainerFailure() {
        assertContainerFailure("sh.zolt.junit.AfterAllFailureFixture", "teardown failed");
    }

    @Test
    void serverModeTreatsAssumptionAbortedContainersAsFailure() {
        WorkerInvocation invocation = runServer("sh.zolt.junit.AbortedContainerFixture");

        assertEquals(0, invocation.serverExitCode());
        assertTrue(invocation.output().contains("Tests failed: 0"), invocation.output());
        assertTrue(invocation.output().contains("Containers aborted: 1"), invocation.output());
        assertTrue(invocation.output().contains("ZOLT_WORKER_RESULT\tid=request-1\texit=1"), invocation.output());
    }

    @Test
    void pooledProcessReturnsFailureForLifecycleContainersAcrossRequests() {
        Path testOutput = codeSource(JunitLauncherFailureStatusTest.class);
        List<Path> runtimeClasspath = List.of(
                testOutput,
                codeSource(org.junit.jupiter.api.Test.class));
        JunitWorkerProcessLauncher launcher = new JunitWorkerProcessLauncher(
                javaExecutable(),
                compiledWorkerClasspath());

        try (JunitWorkerProcess process = launcher.start(
                Path.of(".").toAbsolutePath().normalize(),
                runtimeClasspath)) {
            JunitWorkerClient.WorkerRunResult beforeAll = process.run(
                    testOutput,
                    selection("sh.zolt.junit.BeforeAllFailureFixture"));
            JunitWorkerClient.WorkerRunResult afterAll = process.run(
                    testOutput,
                    selection("sh.zolt.junit.AfterAllFailureFixture"));

            assertEquals(1, beforeAll.exitCode(), beforeAll.output());
            assertEquals(1, afterAll.exitCode(), afterAll.output());
            assertTrue(beforeAll.output().contains("setup failed"), beforeAll.output());
            assertTrue(afterAll.output().contains("teardown failed"), afterAll.output());
        }
    }

    private void assertContainerFailure(String className, String message) {
        WorkerInvocation invocation = runServer(className);

        assertEquals(0, invocation.serverExitCode());
        assertTrue(invocation.output().contains("Tests failed: 0"), invocation.output());
        assertTrue(invocation.output().contains(message), invocation.output());
        assertTrue(invocation.output().contains("ZOLT_WORKER_RESULT\tid=request-1\texit=1"), invocation.output());
    }

    private WorkerInvocation runServer(String className) {
        String request = JunitWorkerProtocol.runRequest(
                "request-1",
                Path.of("target/test-classes"),
                selection(className),
                Optional.empty(),
                List.of());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exitCode = new JunitLauncherWorker().run(
                new String[] {"--server"},
                new ByteArrayInputStream((request + "\n" + JunitWorkerProtocol.quitRequest("request-2") + "\n")
                        .getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        return new WorkerInvocation(exitCode, stdout.toString(StandardCharsets.UTF_8));
    }

    private static TestSelection selection(String className) {
        return TestSelection.fromFields(
                List.of(className),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private static Path currentJavac() {
        return Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "javac.exe" : "javac");
    }

    private static Path codeSource(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();
        } catch (java.net.URISyntaxException exception) {
            throw new AssertionError("Could not resolve classpath for " + type.getName(), exception);
        }
    }

    private static List<Path> compiledWorkerClasspath() {
        return java.util.stream.Stream.of(
                        JunitLauncherWorker.class,
                        JunitWorkerProtocol.class,
                        WorkerFailureDiagnostic.class,
                        TestSelection.class,
                        org.junit.jupiter.api.Test.class)
                .map(JunitLauncherFailureStatusTest::codeSource)
                .distinct()
                .toList();
    }

    private record WorkerInvocation(int serverExitCode, String output) {
    }
}

final class BeforeAllFailureFixture {
    @org.junit.jupiter.api.BeforeAll
    static void brokenSetup() {
        throw new IllegalStateException("setup failed");
    }

    @Test
    void passingTest() {
    }
}

final class AfterAllFailureFixture {
    @Test
    void passingTest() {
    }

    @org.junit.jupiter.api.AfterAll
    static void brokenTeardown() {
        throw new IllegalStateException("teardown failed");
    }
}

final class AbortedContainerFixture {
    @org.junit.jupiter.api.BeforeAll
    static void abortContainer() {
        org.junit.jupiter.api.Assumptions.assumeTrue(false, "container assumption");
    }

    @Test
    void passingTest() {
    }
}
