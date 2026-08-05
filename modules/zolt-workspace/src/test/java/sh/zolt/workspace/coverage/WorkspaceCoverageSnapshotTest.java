package sh.zolt.workspace.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.coverage.CoverageReportSettings;
import sh.zolt.build.coverage.CoverageTooling;
import sh.zolt.build.run.JavaRunResult;
import sh.zolt.build.testruntime.TestReportSettings;
import sh.zolt.build.testruntime.TestRunResult;
import sh.zolt.build.testruntime.compile.TestCompileResult;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
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
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceSelection;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.test.WorkspaceTestResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceCoverageSnapshotTest {
    @TempDir
    private Path tempDir;

    @Test
    void lockfileAndCoverageFloorsStayBoundToCapturedPlan()
            throws Exception {
        Path root = tempDir.resolve("workspace");
        Path memberDir = root.resolve("apps/api");
        Path configPath = root.resolve("zolt.toml");
        Path lockfilePath = root.resolve("zolt.lock");
        String lockAContent = "version = 1\n";
        Files.createDirectories(memberDir);
        Files.writeString(configPath, workspaceConfig(80));
        Files.writeString(memberDir.resolve("zolt.toml"), memberToml());
        Files.writeString(lockfilePath, lockAContent);

        ProjectConfig memberConfig = projectConfig();
        Workspace workspace = new Workspace(
                root,
                configPath,
                new WorkspaceConfig(
                        "workspace",
                        List.of("apps/api"),
                        List.of("apps/api"),
                        Map.of(),
                        Map.of()),
                List.of(new WorkspaceMember(
                        "apps/api",
                        memberDir,
                        memberConfig)),
                List.of(),
                List.of("apps/api"),
                WorkspaceInputs.captured(
                        Map.of(
                                configPath,
                                workspaceConfig(80).getBytes(
                                        StandardCharsets.UTF_8),
                                lockfilePath,
                                lockAContent.getBytes(
                                        StandardCharsets.UTF_8)),
                        Set.of()));
        ZoltLockfile lockA = new ZoltLockfile(1, List.of(), List.of());
        WorkspaceBuildPlan plan = new WorkspaceBuildPlan(
                workspace,
                new WorkspaceSelection(
                        List.of("apps/api"),
                        List.of("apps/api")),
                Optional.empty(),
                lockA);
        WorkspaceBuildResult buildResult = buildResult(memberDir);
        CountDownLatch toolingRequested = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        WorkspaceCoverageService service = new WorkspaceCoverageService(
                start -> workspace,
                (captured, cacheRoot) ->
                        new WorkspaceResolveSnapshot(
                                new ResolveResult(
                                        0,
                                        0,
                                        0,
                                        lockfilePath),
                                lockAContent.getBytes(
                                        StandardCharsets.UTF_8),
                                lockA),
                tests(plan, buildResult),
                reporter(lockA, toolingRequested, resume));

        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> service.runCoverage(
                    root,
                    root.resolve("cache"),
                    WorkspaceSelectionRequest.defaults(),
                    TestSelection.empty(),
                    CoverageReportSettings.defaults(),
                    List.of()));
            assertTrue(toolingRequested.await(5, TimeUnit.SECONDS));
            Files.writeString(configPath, workspaceConfig(10));
            Files.writeString(lockfilePath, "version = 2\n");
            resume.countDown();

            WorkspaceCoverageResult coverage =
                    result.get(5, TimeUnit.SECONDS);
            assertEquals(
                    Optional.of(80.0),
                    coverage.coverageSettings().minLine());
            assertTrue(Files.readString(configPath).contains("10"));
            assertTrue(Files.readString(lockfilePath).contains("2"));
        } finally {
            resume.countDown();
        }
    }

    private static WorkspaceCoverageService.CoverageWorkspaceTests tests(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult) {
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
                return buildResult;
            }

            @Override
            public WorkspaceTestResult runTests(
                    WorkspaceBuildPlan requestedPlan,
                    WorkspaceBuildResult requestedBuild,
                    Path cacheRoot,
                    TestSelection selection,
                    TestJvmArguments jvmArguments,
                    TestReportSettings reportSettings,
                    List<String> events,
                    String suite,
                    TestShardSpec shard) {
                return new WorkspaceTestResult(
                        Optional.empty(),
                        buildResult.members(),
                        List.of(new WorkspaceTestResult.MemberTestRunResult(
                                "apps/api",
                                new TestRunResult(
                                        testCompile(
                                                requestedPlan.workspace()
                                                        .members()
                                                        .getFirst()
                                                        .directory()),
                                        "tests\n"))));
            }
        };
    }

    private static WorkspaceCoverageService.CoverageReporter reporter(
            ZoltLockfile lockA,
            CountDownLatch toolingRequested,
            CountDownLatch resume) {
        return new WorkspaceCoverageService.CoverageReporter() {
            @Override
            public CoverageTooling lockedCoverageTooling(
                    ZoltLockfile lockfile,
                    Path cacheRoot) {
                assertSame(lockA, lockfile);
                toolingRequested.countDown();
                await(resume);
                return new CoverageTooling(
                        cacheRoot.resolve("agent-runtime.jar"),
                        List.of(cacheRoot.resolve("cli.jar")));
            }

            @Override
            public TestJvmArguments coverageJvmArguments(
                    Path agentJar,
                    Path execFile,
                    boolean append) {
                return TestJvmArguments.empty();
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
                return new JavaRunResult(
                        "org.jacoco.cli.internal.Main",
                        "report\n");
            }
        };
    }

    private static WorkspaceBuildResult buildResult(Path memberDir) {
        Classpath empty = new Classpath(List.of());
        return new WorkspaceBuildResult(
                Optional.empty(),
                List.of(new WorkspaceBuildResult.MemberBuildResult(
                        "apps/api",
                        build(memberDir),
                        new ClasspathSet(
                                empty,
                                empty,
                                empty,
                                empty,
                                empty,
                                empty),
                        List.of())));
    }

    private static TestCompileResult testCompile(Path memberDir) {
        return new TestCompileResult(
                build(memberDir),
                0,
                0,
                memberDir.resolve("target/test-classes"),
                "");
    }

    private static BuildResult build(Path memberDir) {
        return new BuildResult(
                Optional.empty(),
                0,
                0,
                memberDir.resolve("target/classes"),
                "");
    }

    private static ProjectConfig projectConfig() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(
                        "api",
                        "0.1.0",
                        "com.example",
                        "21",
                        Optional.empty()),
                Map.of(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    private static String workspaceConfig(int minLine) {
        return """
                [workspace]
                name = "workspace"
                members = ["apps/api"]

                [coverage]
                minLine = %d
                """.formatted(minLine);
    }

    private static String memberToml() {
        return """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = "21"
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
}
