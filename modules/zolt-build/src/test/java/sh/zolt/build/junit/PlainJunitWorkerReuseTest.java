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

    @Test
    void reusesPersistentWorkerAcrossRequestsForOneProject() {
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        List<Path> projectDirectories = new ArrayList<>();
        List<List<Path>> runtimeClasspaths = new ArrayList<>();
        PlainJunitWorkerPoolRunner runner =
                PlainJunitWorkerPoolRunner.persistent((
                        javaExecutable,
                        workerClasspath,
                        projectDirectory,
                        testRuntimeClasspath,
                        jvmArguments,
                        environment) -> {
                    opens.incrementAndGet();
                    return dynamicSession(
                            closes,
                            projectDirectories,
                            runtimeClasspaths);
                });
        Path firstProject = tempDir.resolve("modules/first");
        Path secondProject = firstProject;
        List<Path> firstClasspath = List.of(firstProject.resolve("target/classes"));
        List<Path> secondClasspath =
                List.of(secondProject.resolve("target/test-classes"));

        PlainJunitWorkerPoolRunResult first =
                runPersistent(runner, firstProject, firstClasspath);
        PlainJunitWorkerPoolRunResult second =
                runPersistent(runner, secondProject, secondClasspath);

        assertEquals(1, opens.get());
        assertEquals(0, closes.get());
        assertEquals(1, first.workerStarts());
        assertEquals(0, second.workerStarts());
        assertEquals(1, first.workerRequests());
        assertEquals(1, second.workerRequests());
        assertEquals(List.of(firstProject, secondProject), projectDirectories);
        assertEquals(List.of(firstClasspath, secondClasspath), runtimeClasspaths);

        runner.close();

        assertEquals(1, closes.get());
    }

    @Test
    void isolatesPersistentWorkersAcrossProjectDirectories() {
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        List<Path> projectDirectories = new ArrayList<>();
        PlainJunitWorkerPoolRunner runner =
                PlainJunitWorkerPoolRunner.persistent((
                        javaExecutable,
                        workerClasspath,
                        projectDirectory,
                        testRuntimeClasspath,
                        jvmArguments,
                        environment) -> {
                    opens.incrementAndGet();
                    return dynamicSession(
                            closes,
                            projectDirectories,
                            new ArrayList<>());
                });
        Path firstProject = tempDir.resolve("modules/first");
        Path secondProject = tempDir.resolve("modules/second");

        PlainJunitWorkerPoolRunResult first = runPersistent(
                runner,
                firstProject,
                List.of(firstProject.resolve("target/classes")));
        PlainJunitWorkerPoolRunResult second = runPersistent(
                runner,
                secondProject,
                List.of(secondProject.resolve("target/classes")));

        assertEquals(2, opens.get());
        assertEquals(0, closes.get());
        assertEquals(1, first.workerStarts());
        assertEquals(1, second.workerStarts());
        assertEquals(
                List.of(firstProject, secondProject),
                projectDirectories);

        runner.close();

        assertEquals(2, closes.get());
    }

    @Test
    void retiresAContaminatedWorkerBeforeTheNextProjectRequest() {
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        PlainJunitWorkerPoolRunner runner =
                PlainJunitWorkerPoolRunner.persistent((
                        javaExecutable,
                        workerClasspath,
                        projectDirectory,
                        testRuntimeClasspath,
                        jvmArguments,
                        environment) -> retiringSession(
                                closes,
                                opens.incrementAndGet() == 1));
        Path firstProject = tempDir.resolve("modules/first");
        Path secondProject = firstProject;

        runPersistent(
                runner,
                firstProject,
                List.of(firstProject.resolve("target/classes")));
        runPersistent(
                runner,
                secondProject,
                List.of(secondProject.resolve("target/classes")));

        assertEquals(2, opens.get());
        assertEquals(1, closes.get());

        runner.close();

        assertEquals(2, closes.get());
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

    private PlainJunitWorkerPoolRunResult runPersistent(
            PlainJunitWorkerPoolRunner runner,
            Path projectDirectory,
            List<Path> runtimeClasspath) {
        return runner.run(
                Path.of("java"),
                List.of(Path.of("zolt-worker.jar")),
                projectDirectory,
                config(),
                runtimeClasspath,
                projectDirectory.resolve("target/test-classes"),
                TestSelection.empty(),
                new TestWorkerPoolPlan(true, 1, List.of()),
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

    private static PlainJunitWorkerSession dynamicSession(
            AtomicInteger closes,
            List<Path> projectDirectories,
            List<List<Path>> runtimeClasspaths) {
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
                projectDirectories.add(projectDirectory);
                runtimeClasspaths.add(List.copyOf(testRuntimeClasspath));
                return successful("ok\n");
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

    private static PlainJunitWorkerSession retiringSession(
            AtomicInteger closes,
            boolean retire) {
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
                return successful("ok\n", retire);
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
        return successful(output, false);
    }

    private static PlainJunitWorkerRunResult successful(
            String output,
            boolean retire) {
        return new PlainJunitWorkerRunResult(
                new JunitWorkerClient.WorkerRunResult(
                        output,
                        0,
                        retire),
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
