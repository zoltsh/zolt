package sh.zolt.build.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.junit.JunitWorkerClient;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.shard.TestWorkerPoolPlan;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PlainJunitWorkerRetentionTest {
    private static final int PROJECT_COUNT = 203;

    @TempDir
    private Path tempDir;

    @Test
    void retainsWorkersOnlyForTheCurrentProjectAcrossLargeWorkspaces() {
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        PlainJunitWorkerPoolRunner runner =
                PlainJunitWorkerPoolRunner.persistent((
                        javaExecutable,
                        workerClasspath,
                        projectDirectory,
                        testRuntimeClasspath,
                        jvmArguments,
                        environment) -> {
                    opens.incrementAndGet();
                    maximumActive.accumulateAndGet(
                            active.incrementAndGet(),
                            Math::max);
                    return session(() -> {
                        closes.incrementAndGet();
                        active.decrementAndGet();
                    });
                });

        for (int index = 0; index < PROJECT_COUNT; index++) {
            Path project = tempDir.resolve("modules/project-" + index);
            runPersistent(runner, project);
            assertEquals(1, active.get());
        }

        assertEquals(PROJECT_COUNT, opens.get());
        assertEquals(PROJECT_COUNT - 1, closes.get());
        assertEquals(1, maximumActive.get());

        runner.close();

        assertEquals(PROJECT_COUNT, closes.get());
        assertEquals(0, active.get());
    }

    private void runPersistent(
            PlainJunitWorkerPoolRunner runner,
            Path projectDirectory) {
        runner.run(
                Path.of("java"),
                List.of(Path.of("zolt-worker.jar")),
                projectDirectory,
                config(),
                List.of(projectDirectory.resolve("target/classes")),
                projectDirectory.resolve("target/test-classes"),
                TestSelection.empty(),
                new TestWorkerPoolPlan(true, 1, List.of()),
                TestJvmArguments.empty(),
                Map.of(),
                Optional.empty(),
                List.of(),
                Optional.empty());
    }

    private static PlainJunitWorkerSession session(Runnable close) {
        return new PlainJunitWorkerSession() {
            @Override
            public PlainJunitWorkerRunResult run(
                    Path testOutputDirectory,
                    TestSelection selection,
                    Optional<Path> reportsDirectory,
                    List<String> events,
                    Optional<Path> profileDirectory) {
                throw new AssertionError("Expected dynamic worker request");
            }

            @Override
            public PlainJunitWorkerRunResult run(
                    Path projectDirectory,
                    List<Path> testRuntimeClasspath,
                    Path testOutputDirectory,
                    TestSelection testSelection,
                    Optional<Path> reportsDirectory,
                    List<String> events,
                    Optional<Path> profileDirectory) {
                return new PlainJunitWorkerRunResult(
                        new JunitWorkerClient.WorkerRunResult(
                                "ok\n",
                                0,
                                false),
                        0L,
                        10L);
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
                close.run();
            }
        };
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
