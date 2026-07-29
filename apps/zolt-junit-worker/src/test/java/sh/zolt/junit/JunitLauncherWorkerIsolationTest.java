package sh.zolt.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.test.TestSelection;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JunitLauncherWorkerIsolationTest {
    @TempDir
    private Path tempDir;

    @Test
    void detectsLeakedNonDaemonThreadsAfterARequest()
            throws InterruptedException {
        Set<Long> threadsBefore =
                JunitLauncherWorker.liveNonDaemonThreadIds();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread leaked = Thread.ofPlatform()
                .daemon(false)
                .name("zolt-junit-leak-probe")
                .unstarted(() -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
        try {
            leaked.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(JunitLauncherWorker
                    .hasNewLiveNonDaemonThreads(threadsBefore));
        } finally {
            release.countDown();
            leaked.join(TimeUnit.SECONDS.toMillis(5));
        }
        assertFalse(leaked.isAlive());
    }

    @Test
    void serverModeUsesFreshClassloaderForEveryRequest() {
        TestSelection selection = selection();
        String first = request("request-1", selection);
        String second = request("request-2", selection);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exitCode = new JunitLauncherWorker().run(
                new String[] {"--server"},
                new ByteArrayInputStream((
                        first
                                + "\n"
                                + second
                                + "\n"
                                + JunitWorkerProtocol.quitRequest(
                                        "request-3")
                                + "\n")
                        .getBytes(StandardCharsets.UTF_8)),
                new PrintStream(
                        stdout,
                        true,
                        StandardCharsets.UTF_8),
                new PrintStream(
                        stderr,
                        true,
                        StandardCharsets.UTF_8));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertEquals(
                2,
                output.split("Tests succeeded: 1", -1).length - 1,
                output + "\n" + stderr.toString(StandardCharsets.UTF_8));
        assertTrue(output.contains(
                "ZOLT_WORKER_RESULT\tid=request-1\texit=0"));
        assertTrue(output.contains(
                "ZOLT_WORKER_RESULT\tid=request-2\texit=0"));
    }

    @Test
    void clientReusesRealServerWithFreshRequestClassloaders() {
        JunitWorkerProcessLauncher launcher =
                new JunitWorkerProcessLauncher(
                        javaExecutable(),
                        compiledWorkerClasspath());

        try (JunitWorkerProcess process = launcher.start(
                Path.of(".").toAbsolutePath().normalize(),
                runtimeClasspath())) {
            JunitWorkerClient.WorkerRunResult first = process.run(
                    codeSource(StaticIsolationFixture.class),
                    selection());
            JunitWorkerClient.WorkerRunResult second = process.run(
                    codeSource(StaticIsolationFixture.class),
                    selection());

            assertEquals(0, first.exitCode(), first.output());
            assertEquals(0, second.exitCode(), second.output());
            assertTrue(first.output().contains("Tests succeeded: 1"));
            assertTrue(second.output().contains("Tests succeeded: 1"));
        }
    }

    @Test
    void clientReusesRealServerAcrossProjectRelativePaths()
            throws Exception {
        Path firstOutput =
                codeSource(StaticIsolationFixture.class);
        Path firstProject =
                firstOutput.getParent().getParent();
        Path secondProject = tempDir.resolve("second-project");
        Path secondOutput =
                secondProject.resolve("target/test-classes");
        copyFixture(firstOutput, secondOutput);
        List<Path> secondRuntimeClasspath =
                runtimeClasspath().stream()
                        .map(path -> path.equals(firstOutput)
                                ? secondOutput
                                : path)
                        .toList();
        JunitWorkerProcessLauncher launcher =
                new JunitWorkerProcessLauncher(
                        javaExecutable(),
                        compiledWorkerClasspath());

        try (JunitWorkerProcess process = launcher.start(
                firstProject,
                runtimeClasspath())) {
            JunitWorkerClient.WorkerRunResult first =
                    process.run(
                            firstProject,
                            runtimeClasspath(),
                            Path.of("target/test-classes"),
                            selection(),
                            Optional.empty(),
                            List.of(),
                            Optional.empty());
            JunitWorkerClient.WorkerRunResult second =
                    process.run(
                            secondProject,
                            secondRuntimeClasspath,
                            Path.of("target/test-classes"),
                            selection(),
                            Optional.empty(),
                            List.of(),
                            Optional.empty());

            assertEquals(0, first.exitCode(), first.output());
            assertEquals(0, second.exitCode(), second.output());
            assertTrue(first.output().contains("Tests succeeded: 1"));
            assertTrue(second.output().contains("Tests succeeded: 1"));
        }
    }

    private static String request(
            String requestId,
            TestSelection selection) {
        return JunitWorkerProtocol.runRequest(
                requestId,
                runtimeClasspath(),
                codeSource(StaticIsolationFixture.class),
                selection,
                Optional.empty(),
                List.of(),
                Optional.empty());
    }

    private static TestSelection selection() {
        return TestSelection.fromFields(
                List.of("sh.zolt.junit.StaticIsolationFixture"),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name", "")
                .startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(
                System.getProperty("java.home"),
                "bin",
                executable);
        assertTrue(Files.exists(java), java + " should exist");
        return java;
    }

    private static List<Path> compiledWorkerClasspath() {
        List<Path> classpath = List.of(
                Path.of("target/classes"),
                Path.of("../../modules/zolt-junit-client/target/classes"),
                Path.of("../../modules/zolt-test-model/target/classes"),
                Path.of("../../modules/zolt-model/target/classes")).stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
        for (Path entry : classpath) {
            assertTrue(Files.exists(entry), entry + " should exist");
        }
        return classpath;
    }

    private static List<Path> runtimeClasspath() {
        return Stream.of(
                        codeSource(StaticIsolationFixture.class),
                        codeSource(org.junit.jupiter.api.Test.class))
                .distinct()
                .toList();
    }

    private static Path codeSource(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toAbsolutePath()
                    .normalize();
        } catch (java.net.URISyntaxException exception) {
            throw new AssertionError(
                    "Could not resolve classpath for " + type.getName(),
                    exception);
        }
    }

    private static void copyFixture(
            Path sourceOutput,
            Path targetOutput) throws java.io.IOException {
        Path fixtureClass = Path.of(
                "sh",
                "zolt",
                "junit",
                "StaticIsolationFixture.class");
        Files.createDirectories(
                targetOutput.resolve(fixtureClass).getParent());
        Files.copy(
                sourceOutput.resolve(fixtureClass),
                targetOutput.resolve(fixtureClass));
    }
}

final class StaticIsolationFixture {
    private static int runs;

    @Test
    void startsWithFreshStaticState() {
        assertEquals(1, ++runs);
    }
}
