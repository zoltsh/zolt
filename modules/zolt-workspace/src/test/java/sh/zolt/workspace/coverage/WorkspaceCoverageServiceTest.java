package sh.zolt.workspace.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.coverage.CoverageReportSettings;
import sh.zolt.build.coverage.CoverageTooling;
import sh.zolt.build.run.JavaRunResult;
import sh.zolt.build.testruntime.compile.TestCompileResult;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.build.testruntime.TestReportSettings;
import sh.zolt.build.testruntime.TestRunResult;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.test.TestSelection;
import sh.zolt.test.shard.TestShardSpec;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.resolve.WorkspaceResolveSnapshot;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.service.WorkspaceInputs;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceSelection;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.test.WorkspaceTestResult;
import sh.zolt.workspace.test.WorkspaceTestToolchainMetrics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceCoverageServiceTest {
    @TempDir
    private Path tempDir;

    @Test
    void runsSelectedWorkspaceMembersAndWritesAggregateReport() throws IOException {
        Path workspaceRoot = tempDir.resolve("workspace");
        Path apiDir = workspaceRoot.resolve("apps/api");
        Path coreDir = workspaceRoot.resolve("modules/core");
        Files.createDirectories(apiDir.resolve("src/main/java"));
        Files.createDirectories(coreDir.resolve("src/main/java"));
        ProjectConfig apiConfig = config("api");
        ProjectConfig coreConfig = config("core");
        Workspace workspace = capturedWorkspace(new Workspace(
                workspaceRoot,
                workspaceRoot.resolve("zolt.toml"),
                new WorkspaceConfig(
                        "workspace",
                        List.of("modules/core", "apps/api"),
                        List.of("apps/api"),
                        Map.of(),
                        Map.of()),
                List.of(
                        new WorkspaceMember("modules/core", coreDir, coreConfig),
                        new WorkspaceMember("apps/api", apiDir, apiConfig)),
                List.of(),
                List.of("modules/core", "apps/api")));
        WorkspaceBuildPlan plan = new WorkspaceBuildPlan(
                workspace,
                new WorkspaceSelection(List.of("modules/core", "apps/api"), List.of("apps/api")),
                Optional.empty(),
                new ZoltLockfile(ZoltLockfile.CURRENT_VERSION, List.of(), List.of()));
        WorkspaceBuildResult buildResult = new WorkspaceBuildResult(
                Optional.empty(),
                List.of(
                        memberBuild("modules/core", coreDir),
                        memberBuild("apps/api", apiDir)));
        List<TestJvmArguments> testJvmArguments = new ArrayList<>();
        List<Path> reportClassfileRoots = new ArrayList<>();
        List<Path> reportSourceRoots = new ArrayList<>();
        Path agentJar = tempDir.resolve("org.jacoco.agent-0.8.14-runtime.jar");
        Path cliJar = tempDir.resolve("org.jacoco.cli-0.8.14.jar");
        Path staleWorkerExec = workspaceRoot.resolve(
                "target/coverage/workers/worker-1/jacoco.exec");
        Files.createDirectories(staleWorkerExec.getParent());
        Files.writeString(staleWorkerExec, "stale\n");
        AtomicBoolean workerExecsMerged = new AtomicBoolean();
        WorkspaceCoverageService service = new WorkspaceCoverageService(
                startDirectory -> workspace,
                (requestedWorkspace, cacheRoot) -> {
                    assertEquals(workspace, requestedWorkspace);
                    return resolveSnapshot(workspace, 2);
                },
                new WorkspaceCoverageService.CoverageWorkspaceTests() {
                    @Override
                    public WorkspaceBuildPlan planTests(
                            Path startDirectory,
                            Path cacheRoot,
                            WorkspaceSelectionRequest selectionRequest) {
                        assertEquals(apiDir, startDirectory);
                        assertTrue(selectionRequest.all());
                        return plan;
                    }

                    @Override
                    public WorkspaceBuildResult buildTestInputs(WorkspaceBuildPlan requestedPlan, Path cacheRoot) {
                        assertEquals(plan, requestedPlan);
                        return buildResult;
                    }

                    @Override
                    public WorkspaceTestResult runTests(
                            WorkspaceBuildPlan requestedPlan,
                            WorkspaceBuildResult requestedBuildResult,
                            Path cacheRoot,
                            TestSelection testSelection,
                            TestJvmArguments jvmArguments,
                            TestReportSettings reportSettings,
                            List<String> cliEvents,
                            String suiteName,
                            TestShardSpec shard) {
                        assertEquals(plan, requestedPlan);
                        assertEquals(buildResult, requestedBuildResult);
                        assertEquals("all", suiteName);
                        assertEquals(null, shard);
                        assertFalse(Files.exists(staleWorkerExec));
                        assertEquals(Optional.of(Path.of("target/coverage/test-reports")), reportSettings.reportsDirectory());
                        testJvmArguments.add(jvmArguments);
                        return new WorkspaceTestResult(
                                Optional.empty(),
                                requestedBuildResult.members(),
                                List.of(new WorkspaceTestResult.MemberTestRunResult(
                                        "apps/api",
                                        new TestRunResult(
                                                testCompile(apiDir),
                                                "api tests\n",
                                                TestRunResult.metrics("junit-console", 1, 1, 1, -1L, -1L),
                                                testSelection,
                                                jvmArguments,
                                                Optional.of(apiDir.resolve("target/coverage/test-reports/apps/api"))))),
                                2,
                                Optional.empty(),
                                new WorkspaceTestToolchainMetrics(
                                        1,
                                        1,
                                        1,
                                        1,
                                        0));
                    }
                },
                new WorkspaceCoverageService.CoverageReporter() {
                    @Override
                    public CoverageTooling lockedCoverageTooling(
                            ZoltLockfile lockfile,
                            Path cacheRoot) {
                        assertEquals(plan.lockfile(), lockfile);
                        return new CoverageTooling(agentJar, List.of(cliJar));
                    }

                    @Override
                    public TestJvmArguments coverageJvmArguments(Path requestedAgentJar, Path execFile, boolean append) {
                        assertEquals(agentJar, requestedAgentJar);
                        assertEquals(workspaceRoot.resolve("target/coverage/jacoco.exec"), execFile);
                        assertTrue(append);
                        return new TestJvmArguments(List.of("-javaagent:" + requestedAgentJar + "=destfile=" + execFile + ",append=true"));
                    }

                    @Override
                    public void mergeWorkerExecFilesIfPresent(
                            Path projectRoot,
                            ProjectConfig config,
                            Path execFile,
                            List<Path> cliClasspath) {
                        assertEquals(workspaceRoot, projectRoot);
                        assertEquals(apiConfig, config);
                        assertEquals(
                                workspaceRoot.resolve(
                                        "target/coverage/jacoco.exec"),
                                execFile);
                        assertEquals(List.of(cliJar), cliClasspath);
                        workerExecsMerged.set(true);
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
                        assertTrue(workerExecsMerged.get());
                        assertEquals(workspaceRoot, projectRoot);
                        assertEquals(apiConfig, config);
                        assertEquals(workspaceRoot.resolve("target/coverage/jacoco.exec"), execFile);
                        assertEquals(List.of(cliJar), cliClasspath);
                        reportClassfileRoots.addAll(classfileRoots);
                        reportSourceRoots.addAll(sourceRoots);
                        return new JavaRunResult("org.jacoco.cli.internal.Main", "aggregate report\n");
                    }
                });

        WorkspaceCoverageResult result = service.runCoverage(
                apiDir,
                tempDir.resolve("cache"),
                new WorkspaceSelectionRequest(true, List.of()),
                TestSelection.empty(),
                CoverageReportSettings.defaults(),
                List.of("failed"));

        assertEquals(workspaceRoot.resolve("target/coverage/jacoco.exec"), result.execFile());
        assertEquals(Optional.of(workspaceRoot.resolve("target/coverage/jacoco.xml")), result.xmlReport());
        assertEquals(Optional.of(workspaceRoot.resolve("target/coverage/html")), result.htmlDirectory());
        assertEquals(1, result.members().size());
        assertEquals("apps/api", result.members().getFirst().member());
        assertEquals(2, result.totalMemberCount());
        assertEquals(
                new WorkspaceTestToolchainMetrics(1, 1, 1, 1, 0),
                result.toolchainMetrics());
        assertEquals(
                result.toolchainMetrics(),
                result.testResult().toolchainMetrics());
        assertTrue(workerExecsMerged.get());
        assertEquals("aggregate report\n", result.reportOutput());
        assertEquals(1, testJvmArguments.size());
        assertTrue(testJvmArguments.getFirst().values().getFirst().contains("append=true"));
        assertEquals(List.of(
                coreDir.resolve("target/classes").toAbsolutePath().normalize(),
                apiDir.resolve("target/classes").toAbsolutePath().normalize()), reportClassfileRoots);
        assertEquals(List.of(
                coreDir.resolve("src/main/java").toAbsolutePath().normalize(),
                apiDir.resolve("src/main/java").toAbsolutePath().normalize()), reportSourceRoots);
    }

    @Test
    void shardCoverageUsesShardSpecificAggregateOutputsAndMemberReports() throws IOException {
        Path workspaceRoot = tempDir.resolve("workspace");
        Path apiDir = workspaceRoot.resolve("apps/api");
        Files.createDirectories(apiDir.resolve("src/main/java"));
        ProjectConfig apiConfig = config("api");
        Workspace workspace = capturedWorkspace(new Workspace(
                workspaceRoot,
                workspaceRoot.resolve("zolt.toml"),
                new WorkspaceConfig(
                        "workspace",
                        List.of("apps/api"),
                        List.of("apps/api"),
                        Map.of(),
                        Map.of()),
                List.of(new WorkspaceMember("apps/api", apiDir, apiConfig)),
                List.of(),
                List.of("apps/api")));
        WorkspaceBuildPlan plan = new WorkspaceBuildPlan(
                workspace,
                new WorkspaceSelection(List.of("apps/api"), List.of("apps/api")),
                Optional.empty(),
                new ZoltLockfile(ZoltLockfile.CURRENT_VERSION, List.of(), List.of()));
        WorkspaceBuildResult buildResult = new WorkspaceBuildResult(
                Optional.empty(),
                List.of(memberBuild("apps/api", apiDir)));
        List<TestReportSettings> reportSettings = new ArrayList<>();
        List<TestShardSpec> shards = new ArrayList<>();
        Path agentJar = tempDir.resolve("org.jacoco.agent-0.8.14-runtime.jar");
        Path cliJar = tempDir.resolve("org.jacoco.cli-0.8.14.jar");
        WorkspaceCoverageService service = new WorkspaceCoverageService(
                startDirectory -> workspace,
                (requestedWorkspace, cacheRoot) ->
                        resolveSnapshot(workspace, 1),
                new WorkspaceCoverageService.CoverageWorkspaceTests() {
                    @Override
                    public WorkspaceBuildPlan planTests(
                            Path startDirectory,
                            Path cacheRoot,
                            WorkspaceSelectionRequest selectionRequest) {
                        return plan;
                    }

                    @Override
                    public WorkspaceBuildResult buildTestInputs(WorkspaceBuildPlan requestedPlan, Path cacheRoot) {
                        return buildResult;
                    }

                    @Override
                    public WorkspaceTestResult runTests(
                            WorkspaceBuildPlan requestedPlan,
                            WorkspaceBuildResult requestedBuildResult,
                            Path cacheRoot,
                            TestSelection testSelection,
                            TestJvmArguments jvmArguments,
                            TestReportSettings requestedReportSettings,
                            List<String> cliEvents,
                            String suiteName,
                            TestShardSpec shard) {
                        assertEquals("fast", suiteName);
                        reportSettings.add(requestedReportSettings);
                        shards.add(shard);
                        return new WorkspaceTestResult(
                                Optional.empty(),
                                requestedBuildResult.members(),
                                List.of(new WorkspaceTestResult.MemberTestRunResult(
                                        "apps/api",
                                        new TestRunResult(
                                                testCompile(apiDir),
                                                "api tests\n",
                                                TestRunResult.metrics("junit-console", 1, 1, 1, -1L, -1L),
                                                testSelection,
                                                jvmArguments,
                                                requestedReportSettings.reportsDirectory()))));
                    }
                },
                new WorkspaceCoverageService.CoverageReporter() {
                    @Override
                    public CoverageTooling lockedCoverageTooling(
                            ZoltLockfile lockfile,
                            Path cacheRoot) {
                        return new CoverageTooling(agentJar, List.of(cliJar));
                    }

                    @Override
                    public TestJvmArguments coverageJvmArguments(Path requestedAgentJar, Path execFile, boolean append) {
                        assertEquals(workspaceRoot.resolve("target/coverage/shards/fast/shard-2-of-4/jacoco.exec"), execFile);
                        return new TestJvmArguments(List.of("-javaagent:" + requestedAgentJar + "=destfile=" + execFile));
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
                        assertEquals(workspaceRoot.resolve("target/coverage/shards/fast/shard-2-of-4/jacoco.exec"), execFile);
                        return new JavaRunResult("org.jacoco.cli.internal.Main", "aggregate report\n");
                    }
                });

        WorkspaceCoverageResult result = service.runCoverage(
                apiDir,
                tempDir.resolve("cache"),
                WorkspaceSelectionRequest.defaults(),
                TestSelection.empty(),
                CoverageReportSettings.defaults(),
                List.of(),
                "fast",
                new TestShardSpec(2, 4));

        Path shardRoot = workspaceRoot.resolve("target/coverage/shards/fast/shard-2-of-4");
        assertEquals(shardRoot.resolve("jacoco.exec"), result.execFile());
        assertEquals(Optional.of(shardRoot.resolve("jacoco.xml")), result.xmlReport());
        assertEquals(Optional.of(shardRoot.resolve("html")), result.htmlDirectory());
        assertEquals(List.of(new TestShardSpec(2, 4)), shards);
        assertEquals(Optional.of(Path.of("target/coverage/test-reports")), reportSettings.getFirst().reportsDirectory());
        assertEquals(Optional.of(Path.of("target/coverage/test-reports")), result.members().getFirst().result().reportsDirectory());
    }

    private static WorkspaceBuildResult.MemberBuildResult memberBuild(String member, Path memberDir) {
        return new WorkspaceBuildResult.MemberBuildResult(
                member,
                build(memberDir),
                emptyClasspaths(),
                List.of());
    }

    private static TestCompileResult testCompile(Path memberDir) {
        return new TestCompileResult(
                build(memberDir),
                1,
                0,
                memberDir.resolve("target/test-classes"),
                "");
    }

    private static BuildResult build(Path memberDir) {
        return new BuildResult(
                Optional.empty(),
                1,
                0,
                memberDir.resolve("target/classes"),
                "");
    }

    private static ClasspathSet emptyClasspaths() {
        Classpath empty = new Classpath(List.of());
        return new ClasspathSet(empty, empty, empty, empty, empty, empty);
    }

    private static Workspace capturedWorkspace(Workspace workspace) {
        String content = """
                [workspace]
                name = "workspace"

                [workspace.members]
                default = ["apps/api"]
                include = ["apps/api", "modules/core"]
                """;
        String lockfileContent = "version = 7\n";
        Path lockfilePath = workspace.root().resolve("zolt.lock");
        try {
            Files.writeString(workspace.configPath(), content);
            Files.writeString(lockfilePath, lockfileContent);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return workspace.withInputs(WorkspaceInputs.captured(
                Map.of(
                        workspace.configPath(),
                        content.getBytes(StandardCharsets.UTF_8),
                        lockfilePath,
                        lockfileContent.getBytes(StandardCharsets.UTF_8)),
                Set.of()));
    }

    private static WorkspaceResolveSnapshot resolveSnapshot(
            Workspace workspace,
            int packageCount) {
        Path lockfilePath = workspace.root().resolve("zolt.lock");
        return new WorkspaceResolveSnapshot(
                new ResolveResult(
                        packageCount,
                        0,
                        0,
                        lockfilePath),
                workspace.inputs().contentBytes(lockfilePath)
                        .orElseThrow(),
                new ZoltLockfile(ZoltLockfile.CURRENT_VERSION, List.of(), List.of()));
    }

    private static ProjectConfig config(String name) {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(name, "0.1.0", "com.example", "21", Optional.empty()),
                Map.of(),
                Map.of(),
                Map.of("org.junit.jupiter:junit-jupiter", "5.11.4"),
                BuildSettings.defaults());
    }
}
