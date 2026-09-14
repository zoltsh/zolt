package sh.zolt.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.error.WorkerFailureDiagnostic;
import sh.zolt.test.TestSelection;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JunitLauncherWorkerContainerFailureTest {
    @TempDir
    private Path tempDir;

    @Test
    void oneShotModeFailsForBeforeAllContainerFailure() throws Exception {
        Path output = compile(
                "BeforeAllFailureTest",
                """
                @BeforeAll
                static void failBeforeAll() {
                    throw new IllegalStateException("before-all failure");
                }
                """);

        ProcessResult result = runOneShot(output);

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("Tests found: 1"), result.output());
        assertTrue(result.output().contains("Tests failed: 0"), result.output());
        assertTrue(result.output().contains("before-all failure"), result.output());
    }

    @Test
    void oneShotModeFailsForAfterAllContainerFailure() throws Exception {
        Path output = compile(
                "AfterAllFailureTest",
                """
                @AfterAll
                static void failAfterAll() {
                    throw new IllegalStateException("after-all failure");
                }
                """);

        ProcessResult result = runOneShot(output);

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("Tests found: 1"), result.output());
        assertTrue(result.output().contains("Tests succeeded: 1"), result.output());
        assertTrue(result.output().contains("Tests failed: 0"), result.output());
        assertTrue(result.output().contains("after-all failure"), result.output());
    }

    @Test
    void oneShotModeAllowsAssumptionAbortedContainer() throws Exception {
        Path output = compile(
                "AssumptionAbortTest",
                """
                @BeforeAll
                static void abortContainer() {
                    Assumptions.assumeTrue(false, "assumption not met");
                }
                """);

        ProcessResult result = runOneShot(output);

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Tests found: 1"), result.output());
        assertTrue(result.output().contains("Tests failed: 0"), result.output());
    }

    @Test
    void oneShotModeAllowsDisabledSkips() throws Exception {
        Path output = compile(
                "DisabledSkipTest",
                """
                @Disabled("intentionally disabled")
                @Test
                void skipped() {
                }
                """);

        ProcessResult result = runOneShot(output);

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Tests found: 2"), result.output());
        assertTrue(result.output().contains("Tests failed: 0"), result.output());
    }

    @Test
    void pooledModeFailsLifecycleContainersAndKeepsServing() throws Exception {
        Path beforeOutput = compile(
                "BeforeAllFailureTest",
                """
                @BeforeAll
                static void failBeforeAll() {
                    throw new IllegalStateException("before-all failure");
                }
                """);
        Path afterOutput = compile(
                "AfterAllFailureTest",
                """
                @AfterAll
                static void failAfterAll() {
                    throw new IllegalStateException("after-all failure");
                }
                """);
        String before = request("before", beforeOutput, "probe.BeforeAllFailureTest");
        String after = request("after", afterOutput, "probe.AfterAllFailureTest");
        String input = before + '\n' + after + '\n' + JunitWorkerProtocol.quitRequest("quit") + '\n';
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int serverExit = new JunitLauncherWorker().run(
                new String[] {"--server"},
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, serverExit, output);
        assertTrue(output.contains("ZOLT_WORKER_RESULT\tid=before\texit=1"), output);
        assertTrue(output.contains("ZOLT_WORKER_RESULT\tid=after\texit=1"), output);
        assertTrue(output.contains("ZOLT_WORKER_RESULT\tid=quit\texit=0"), output);
    }

    @Test
    void pooledModeFailsDiscoveryContainerAndKeepsServing() throws Exception {
        Path testOutput = compile("DiscoveryFailureTest", "");
        TestSelection selection = TestSelection.fromFields(
                List.of(),
                List.of(new TestSelection.MethodSelector(
                        "probe.DiscoveryFailureTest", "missingMethod")),
                List.of(),
                List.of(),
                List.of());
        String request = JunitWorkerProtocol.runRequest(
                "discovery",
                List.of(testOutput, junitRuntime()),
                testOutput,
                selection,
                Optional.empty(),
                List.of(),
                Optional.empty());
        String input = request + '\n' + JunitWorkerProtocol.quitRequest("quit") + '\n';
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int serverExit = new JunitLauncherWorker().run(
                new String[] {"--server"},
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, serverExit, output);
        assertTrue(output.contains("Tests found: 0"), output);
        assertTrue(output.contains("resolution failed"), output);
        assertTrue(output.contains("ZOLT_WORKER_RESULT\tid=discovery\texit=1"), output);
        assertTrue(output.contains("ZOLT_WORKER_RESULT\tid=quit\texit=0"), output);
    }

    private Path compile(String className, String lifecycleMethod) throws IOException {
        Path source = tempDir.resolve(className).resolve("src/probe/" + className + ".java");
        Path output = tempDir.resolve(className).resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(output);
        Files.writeString(source, """
                package probe;

                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.Assumptions;
                import org.junit.jupiter.api.BeforeAll;
                import org.junit.jupiter.api.Disabled;
                import org.junit.jupiter.api.Test;

                public class %s {
                    @Test
                    void passes() {
                    }

                    %s
                }
                """.formatted(className, lifecycleMethod));
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int exitCode = ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                diagnostics,
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                output.toString(),
                source.toString());
        assertEquals(0, exitCode, diagnostics.toString(StandardCharsets.UTF_8));
        return output;
    }

    private static ProcessResult runOneShot(Path testOutput) throws Exception {
        Process process = new ProcessBuilder(
                        javaExecutable().toString(),
                        "-cp",
                        String.join(
                                java.io.File.pathSeparator,
                                testOutput.toString(),
                                codeSource(JunitLauncherWorker.class).toString(),
                                codeSource(TestSelection.class).toString(),
                                codeSource(WorkerFailureDiagnostic.class).toString(),
                                junitRuntime().toString()),
                        JunitLauncherWorker.MAIN_CLASS,
                        testOutput.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.waitFor(), output);
    }

    private static String request(String id, Path testOutput, String className) {
        TestSelection selection = TestSelection.fromFields(
                List.of(className),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        return JunitWorkerProtocol.runRequest(
                id,
                List.of(testOutput, junitRuntime()),
                testOutput,
                selection,
                Optional.empty(),
                List.of(),
                Optional.empty());
    }

    private static Path junitRuntime() {
        return codeSource(Test.class);
    }

    private static Path codeSource(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();
        } catch (java.net.URISyntaxException exception) {
            throw new AssertionError("Could not resolve classpath entry for " + type.getName() + ".", exception);
        }
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
