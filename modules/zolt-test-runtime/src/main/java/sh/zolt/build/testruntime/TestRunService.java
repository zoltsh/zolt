package sh.zolt.build.testruntime;

import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildResultWithClasspaths;
import sh.zolt.build.run.JavaRunner;
import sh.zolt.build.testruntime.compile.TestCompileResult;
import sh.zolt.build.testruntime.compile.TestCompileResultWithClasspaths;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.testruntime.compile.TestCompileService;
import sh.zolt.build.testruntime.execution.CompiledTestExecutionRunner;
import sh.zolt.build.testruntime.execution.CompiledTestRunner;
import sh.zolt.build.testruntime.execution.CurrentWorkerClasspath;
import sh.zolt.build.testruntime.execution.PlainJunitRunners;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.framework.FrameworkTestRunner;
import sh.zolt.build.junit.PlainJunitWorkerPoolRunner;
import sh.zolt.build.junit.PlainJunitWorkerProcessSessionFactory;
import sh.zolt.build.junit.PlainJunitWorkerProcessRunner;
import sh.zolt.build.junit.PlainJunitWorkerRunner;
import sh.zolt.build.profile.TestProfileSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveService;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.shard.TestShardSpec;
import sh.zolt.test.TestSelection;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

public final class TestRunService extends CompiledTestRunService {
    private final TestCompileService testCompileService;

    public TestRunService() {
        this(new JdkDetector());
    }

    public TestRunService(JdkChecker jdkDetector) {
        this(jdkDetector, FrameworkTestRunner.none());
    }

    public TestRunService(FrameworkTestRunner frameworkTestRunner) {
        this(new JdkDetector(), frameworkTestRunner);
    }

    public TestRunService(JdkChecker jdkDetector, FrameworkTestRunner frameworkTestRunner) {
        this(jdkDetector, frameworkTestRunner, new ResolveService());
    }

    public TestRunService(FrameworkTestRunner frameworkTestRunner, ResolveService resolveService) {
        this(new JdkDetector(), frameworkTestRunner, resolveService);
    }

    public TestRunService(JdkChecker jdkDetector, FrameworkTestRunner frameworkTestRunner, ResolveService resolveService) {
        this(jdkDetector, jdkDetector, frameworkTestRunner, resolveService);
    }

    /** Compiles tests with {@code compileChecker} but RUNS them with {@code runChecker} (the test-runtime toolchain when pinned; otherwise identical). */
    public TestRunService(
            JdkChecker compileChecker,
            JdkChecker runChecker,
            FrameworkTestRunner frameworkTestRunner,
            ResolveService resolveService) {
        this(
                new TestCompileService(compileChecker, resolveService),
                compiledTestRunInvoker(
                        runChecker,
                        frameworkTestRunner));
    }
    TestRunService(
            TestCompileService testCompileService,
            JdkChecker jdkDetector,
            JavaRunner javaRunner,
            FrameworkTestRunner frameworkTestRunner,
            Supplier<List<Path>> plainJunitWorkerClasspath,
            PlainJunitWorkerRunner plainJunitWorkerRunner,
            boolean plainJunitWorkerEnabled,
            String pathSeparator) {
        this(
                testCompileService,
                new CompiledTestRunInvoker(
                        new CompiledTestExecutionRunner(
                                new CompiledTestRunner(
                                        jdkDetector,
                                        javaRunner,
                                        frameworkTestRunner,
                                        plainJunitWorkerClasspath,
                                        plainJunitWorkerRunner,
                                        plainJunitWorkerEnabled,
                                        pathSeparator))));
    }

    private static CompiledTestRunInvoker compiledTestRunInvoker(
            JdkChecker runChecker,
            FrameworkTestRunner frameworkTestRunner) {
        PlainJunitWorkerRunner workerRunner =
                PlainJunitWorkerProcessRunner::run;
        PlainJunitRunners runners = new PlainJunitRunners(
                new CurrentWorkerClasspath()::discover,
                workerRunner,
                new PlainJunitWorkerPoolRunner(
                        new PlainJunitWorkerProcessSessionFactory()));
        return new CompiledTestRunInvoker(
                new CompiledTestExecutionRunner(
                        new CompiledTestRunner(
                                runChecker,
                                new JavaRunner(),
                                frameworkTestRunner,
                                runners,
                                Boolean.getBoolean(
                                        "zolt.junit.worker"),
                                java.io.File.pathSeparator)));
    }

    private TestRunService(
            TestCompileService testCompileService,
            CompiledTestRunInvoker compiledTestRunInvoker) {
        super(compiledTestRunInvoker);
        this.testCompileService = testCompileService;
    }

    /** Returns a service that restores the main build and test classes from the build cache. */
    public TestRunService withBuildCache(BuildCacheService buildCacheService) {
        return new TestRunService(
                testCompileService.withBuildCache(buildCacheService),
                compiledTestRunInvoker());
    }

    public TestRunResult runTests(Path projectDirectory, ProjectConfig config, Path cacheRoot) {
        return runTests(projectDirectory, config, cacheRoot, TestSelection.empty());
    }

    public TestRunResult runTests(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            TestSelection selection) {
        return runTests(projectDirectory, config, cacheRoot, selection, TestJvmArguments.empty());
    }

    public TestRunResult runTests(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            TestSelection selection,
            TestJvmArguments jvmArguments) {
        return runTests(projectDirectory, config, cacheRoot, selection, jvmArguments, TestReportSettings.disabled());
    }

    public TestRunResult runTests(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            TestSelection selection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings) {
        return runTests(projectDirectory, config, cacheRoot, selection, jvmArguments, reportSettings, List.of());
    }

    public TestRunResult runTests(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            TestSelection selection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents) {
        return runTests(projectDirectory, config, cacheRoot, selection, jvmArguments, reportSettings, cliEvents, "all");
    }

    public TestRunResult runTests(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            TestSelection selection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents,
            String suiteName) {
        return runTests(projectDirectory, config, cacheRoot, selection, jvmArguments, reportSettings, cliEvents, suiteName, null);
    }

    public TestRunResult runTests(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            TestSelection selection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents,
            String suiteName,
            TestShardSpec shard) {
        return runTests(
                projectDirectory,
                config,
                cacheRoot,
                selection,
                jvmArguments,
                reportSettings,
                cliEvents,
                suiteName,
                shard,
                TestProfileSettings.disabled());
    }

    public TestRunResult runTests(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            TestSelection selection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents,
            String suiteName,
            TestShardSpec shard,
            TestProfileSettings profileSettings) {
        TestCompileResultWithClasspaths compileResult = compileTests(projectDirectory, config, cacheRoot);
        return runCompiledTests(
                projectDirectory,
                config,
                compileResult.classpaths(),
                compileResult.testCompileResult(),
                selection,
                jvmArguments,
                reportSettings,
                cliEvents,
                suiteName,
                shard,
                profileSettings);
    }

    public TestCompileResultWithClasspaths compileTests(Path projectDirectory, ProjectConfig config, Path cacheRoot) {
        return testCompileService.compileTestsWithClasspaths(projectDirectory, config, cacheRoot);
    }

    public BuildResultWithClasspaths buildTestInputs(Path projectDirectory, ProjectConfig config, Path cacheRoot) {
        return testCompileService.buildTestInputs(projectDirectory, config, cacheRoot);
    }

    public TestRunResult runTests(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            BuildResult buildResult,
            TestSelection selection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents) {
        return runTests(projectDirectory, config, classpaths, buildResult, selection, jvmArguments, reportSettings, cliEvents, "all", null);
    }

    public TestRunResult runTests(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            BuildResult buildResult,
            TestSelection selection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents,
            String suiteName,
            TestShardSpec shard) {
        return runTests(
                projectDirectory,
                config,
                new BuildResultWithClasspaths(
                        buildResult,
                        classpaths,
                        List.of()),
                selection,
                jvmArguments,
                reportSettings,
                cliEvents,
                suiteName,
                shard);
    }

    public TestRunResult runTests(
            Path projectDirectory,
            ProjectConfig config,
            BuildResultWithClasspaths buildResult,
            TestSelection selection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents,
            String suiteName,
            TestShardSpec shard) {
        return TestRunFromBuildResult.run(
                this, projectDirectory, config, buildResult,
                selection, jvmArguments, reportSettings, cliEvents, suiteName, shard);
    }

    public TestCompileResult compileTests(
            Path projectDirectory, ProjectConfig config, ClasspathSet classpaths, BuildResult buildResult) {
        return testCompileService.compileTests(projectDirectory, config, classpaths, buildResult);
    }

    public TestCompileResult compileTests(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            BuildResult buildResult,
            List<ResolvedClasspathPackage> classpathPackages) {
        return testCompileService.compileTests(
                projectDirectory,
                config,
                classpaths,
                buildResult,
                classpathPackages);
    }

    public TestCompileResult compileTests(
            Path projectDirectory,
            ProjectConfig config,
            BuildResultWithClasspaths buildResult) {
        return compileTests(
                projectDirectory,
                config,
                buildResult.classpaths(),
                buildResult.buildResult(),
                buildResult.classpathPackages());
    }
}
