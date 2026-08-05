package sh.zolt.workspace.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildException;
import sh.zolt.build.coverage.CoverageReportSettings;
import sh.zolt.build.coverage.CoverageTooling;
import sh.zolt.build.run.JavaRunResult;
import sh.zolt.build.testruntime.TestReportSettings;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileWriter;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.shard.TestShardSpec;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.resolve.WorkspaceResolveSnapshot;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceInputs;
import sh.zolt.workspace.service.WorkspaceSelection;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.test.WorkspaceTestResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceCoverageResolvePlanRaceTest {
    @TempDir
    private Path tempDir;

    @Test
    void replacementBetweenResolveAndPlanFailsBeforeExecution()
            throws Exception {
        Path root = tempDir.resolve("workspace");
        Path configPath = root.resolve("zolt.toml");
        Path lockfilePath = root.resolve("zolt.lock");
        String config = workspaceConfig();
        ZoltLockfile lockA = lockfile('a');
        ZoltLockfile lockB = lockfile('b');
        String contentA = new ZoltLockfileWriter().write(lockA);
        String contentB = new ZoltLockfileWriter().write(lockB);
        Files.createDirectories(root);
        Files.writeString(configPath, config);

        Workspace resolvedWorkspace = workspace(
                root,
                configPath,
                config,
                Map.of());
        Workspace plannedWorkspace = workspace(
                root,
                configPath,
                config,
                Map.of(
                        lockfilePath,
                        contentB.getBytes(StandardCharsets.UTF_8)));
        WorkspaceBuildPlan plan = new WorkspaceBuildPlan(
                plannedWorkspace,
                new WorkspaceSelection(List.of(), List.of()),
                Optional.empty(),
                lockB);
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean executionStarted = new AtomicBoolean();
        WorkspaceCoverageService service = new WorkspaceCoverageService(
                start -> resolvedWorkspace,
                (workspace, cacheRoot) -> {
                    write(lockfilePath, contentA);
                    committed.countDown();
                    await(resume);
                    return new WorkspaceResolveSnapshot(
                            new ResolveResult(0, 0, 0, lockfilePath),
                            contentA.getBytes(StandardCharsets.UTF_8),
                            lockA);
                },
                tests(plan, executionStarted),
                reporter(executionStarted));

        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> service.runCoverage(
                    root,
                    root.resolve("cache"),
                    WorkspaceSelectionRequest.defaults(),
                    TestSelection.empty(),
                    CoverageReportSettings.defaults(),
                    List.of()));
            assertTrue(committed.await(5, TimeUnit.SECONDS));
            Files.writeString(lockfilePath, contentB);
            resume.countDown();

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            BuildException stale = assertInstanceOf(
                    BuildException.class,
                    failure.getCause());
            assertTrue(stale.getMessage().contains(
                    "changed after coverage resolution"));
            assertFalse(executionStarted.get());
            assertEquals(contentB, Files.readString(lockfilePath));
        } finally {
            resume.countDown();
        }
    }

    private static WorkspaceCoverageService.CoverageWorkspaceTests tests(
            WorkspaceBuildPlan plan,
            AtomicBoolean executionStarted) {
        return new WorkspaceCoverageService.CoverageWorkspaceTests() {
            @Override
            public WorkspaceBuildPlan planTests(
                    Path start,
                    Path cacheRoot,
                    WorkspaceSelectionRequest selection) {
                return plan;
            }

            @Override
            public WorkspaceBuildResult buildTestInputs(
                    WorkspaceBuildPlan requested,
                    Path cacheRoot) {
                executionStarted.set(true);
                throw new AssertionError("Coverage execution must not start.");
            }

            @Override
            public WorkspaceTestResult runTests(
                    WorkspaceBuildPlan requestedPlan,
                    WorkspaceBuildResult buildResult,
                    Path cacheRoot,
                    TestSelection selection,
                    TestJvmArguments jvmArguments,
                    TestReportSettings reportSettings,
                    List<String> cliEvents,
                    String suiteName,
                    TestShardSpec shard) {
                executionStarted.set(true);
                throw new AssertionError("Coverage tests must not start.");
            }
        };
    }

    private static WorkspaceCoverageService.CoverageReporter reporter(
            AtomicBoolean executionStarted) {
        return new WorkspaceCoverageService.CoverageReporter() {
            @Override
            public CoverageTooling lockedCoverageTooling(
                    ZoltLockfile lockfile,
                    Path cacheRoot) {
                executionStarted.set(true);
                throw new AssertionError("Coverage tooling must not load.");
            }

            @Override
            public TestJvmArguments coverageJvmArguments(
                    Path agentJar,
                    Path execFile,
                    boolean append) {
                throw new AssertionError("Coverage arguments must not build.");
            }

            @Override
            public JavaRunResult runReport(
                    Path projectRoot,
                    ProjectConfig config,
                    CoverageReportSettings settings,
                    Path execFile,
                    List<Path> cliClasspath,
                    List<Path> classfileRoots,
                    List<Path> sourceRoots) {
                throw new AssertionError("Coverage report must not run.");
            }
        };
    }

    private static Workspace workspace(
            Path root,
            Path configPath,
            String config,
            Map<Path, byte[]> extraInputs) {
        java.util.LinkedHashMap<Path, byte[]> inputs =
                new java.util.LinkedHashMap<>(extraInputs);
        inputs.put(configPath, config.getBytes(StandardCharsets.UTF_8));
        return new Workspace(
                root,
                configPath,
                new WorkspaceConfig(
                        "workspace",
                        List.of(),
                        List.of(),
                        Map.of(),
                        Map.of()),
                List.of(),
                List.of(),
                List.of(),
                WorkspaceInputs.captured(inputs, Set.of()));
    }

    private static ZoltLockfile lockfile(char identity) {
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.of("sha256:" + String.valueOf(identity).repeat(64)),
                List.of(),
                List.of(),
                List.of());
    }

    private static String workspaceConfig() {
        return """
                [workspace]
                name = "workspace"
                members = []
                """;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for coverage latch.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void write(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
