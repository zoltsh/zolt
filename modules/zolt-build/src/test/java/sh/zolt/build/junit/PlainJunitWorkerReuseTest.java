package sh.zolt.build.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.junit.JunitWorkerClient;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.test.TestInventoryEntry;
import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.runtime.TestRunException;
import sh.zolt.test.shard.TestWorkerPoolPlan;
import sh.zolt.test.shard.TestWorkerPoolWave;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PlainJunitWorkerReuseTest {
    @TempDir
    private Path tempDir;

    @Test
    void reusesStableWorkerSessionAcrossWaves() {
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        List<String> selectedClasses =
                Collections.synchronizedList(new ArrayList<>());
        PlainJunitWorkerPoolRunner runner =
                new PlainJunitWorkerPoolRunner((
                        javaExecutable,
                        workerClasspath,
                        projectDirectory,
                        testRuntimeClasspath,
                        jvmArguments,
                        environment) -> {
                    opens.incrementAndGet();
                    return session(
                            closes,
                            selection -> {
                                String selected = selection
                                        .classSelectors()
                                        .getFirst();
                                selectedClasses.add(selected);
                                return successful(
                                        "ok " + selected + "\n");
                            });
                });

        PlainJunitWorkerPoolRunResult result = run(
                runner,
                List.of(
                        wave("com.example.AlphaTest"),
                        wave("com.example.BetaTest")));

        assertEquals(1, opens.get());
        assertEquals(1, closes.get());
        assertEquals(2, result.workerRequests());
        assertEquals(1, result.workerStarts());
        assertEquals(5L, result.startupNanos());
        assertEquals(
                List.of(
                        "com.example.AlphaTest",
                        "com.example.BetaTest"),
                selectedClasses);
    }

    @Test
    void restartsCrashedWorkerAndRetriesRequestOnce() {
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        PlainJunitWorkerPoolRunner runner =
                new PlainJunitWorkerPoolRunner((
                        javaExecutable,
                        workerClasspath,
                        projectDirectory,
                        testRuntimeClasspath,
                        jvmArguments,
                        environment) -> {
                    int attempt = opens.incrementAndGet();
                    return session(
                            closes,
                            selection -> {
                                if (attempt == 1) {
                                    throw new TestRunException(
                                            "worker exited");
                                }
                                return successful("recovered\n");
                            });
                });

        PlainJunitWorkerPoolRunResult result =
                run(runner, List.of(wave("com.example.AlphaTest")));

        assertEquals(2, opens.get());
        assertEquals(2, closes.get());
        assertEquals(2, result.workerStarts());
        assertEquals(1, result.workerRequests());
        assertEquals("recovered\n", result.output());
    }

    private PlainJunitWorkerPoolRunResult run(
            PlainJunitWorkerPoolRunner runner,
            List<TestWorkerPoolWave> waves) {
        return runner.run(
                Path.of("java"),
                List.of(Path.of("zolt-worker.jar")),
                tempDir,
                config(),
                List.of(tempDir.resolve("target/classes")),
                tempDir.resolve("target/test-classes"),
                TestSelection.empty(),
                new TestWorkerPoolPlan(true, 1, waves),
                TestJvmArguments.empty(),
                Map.of(),
                Optional.empty(),
                List.of(),
                Optional.empty());
    }

    private static PlainJunitWorkerSession session(
            AtomicInteger closes,
            java.util.function.Function<
                    TestSelection,
                    PlainJunitWorkerRunResult> run) {
        return new PlainJunitWorkerSession() {
            @Override
            public PlainJunitWorkerRunResult run(
                    Path testOutputDirectory,
                    TestSelection selection,
                    Optional<Path> reportsDirectory,
                    List<String> events,
                    Optional<Path> profileDirectory) {
                return run.apply(selection);
            }

            @Override
            public long startupNanos() {
                return 5L;
            }

            @Override
            public int processStarts() {
                return 1;
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
    }

    private static PlainJunitWorkerRunResult successful(
            String output) {
        return new PlainJunitWorkerRunResult(
                new JunitWorkerClient.WorkerRunResult(output, 0),
                0L,
                10L);
    }

    private static TestWorkerPoolWave wave(String className) {
        return new TestWorkerPoolWave(
                List.of(entry(className)),
                Map.of());
    }

    private static TestInventoryEntry entry(String className) {
        Path outputRoot = Path.of("target/test-classes");
        return new TestInventoryEntry(
                className,
                outputRoot,
                outputRoot.resolve(
                        className.replace('.', '/') + ".class"),
                List.of("*Test"),
                "junit-jupiter",
                List.of());
    }

    private static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(
                        "demo",
                        "0.1.0",
                        "com.example",
                        "21",
                        Optional.empty()),
                Map.of(
                        "central",
                        "https://repo.maven.apache.org/maven2"),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }
}
