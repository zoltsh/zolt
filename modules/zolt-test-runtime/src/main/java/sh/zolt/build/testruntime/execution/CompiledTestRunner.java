package sh.zolt.build.testruntime.execution;

import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.build.BuildException;
import sh.zolt.build.JavaRunException;
import sh.zolt.build.run.JavaRunResult;
import sh.zolt.build.run.JavaRunner;
import sh.zolt.build.testruntime.compile.TestCompileResult;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.framework.FrameworkTestRunRequest;
import sh.zolt.framework.FrameworkTestRunResult;
import sh.zolt.framework.FrameworkTestRunner;
import sh.zolt.build.junit.PlainJunitWorkerPoolRunResult;
import sh.zolt.build.junit.PlainJunitWorkerPoolRunner;
import sh.zolt.build.junit.PlainJunitWorkerRunResult;
import sh.zolt.build.junit.PlainJunitWorkerRunner;
import sh.zolt.build.profile.TestProfileSettings;
import sh.zolt.build.profile.TestProfileSummaryFormatter;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.build.testruntime.TestReportSettings;
import sh.zolt.test.runtime.TestRunException;
import sh.zolt.build.testruntime.TestRunResult;
import sh.zolt.project.ProjectConfig;
import sh.zolt.test.TestSelection;
import sh.zolt.test.shard.TestWorkerPoolPlan;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

public final class CompiledTestRunner {
    private static final String CONSOLE_MAIN_CLASS = "org.junit.platform.console.ConsoleLauncher";
    private static final String JUNIT_CONSOLE_RUNNER = "junit-console";
    private static final String PLAIN_JUNIT_WORKER_RUNNER = "zolt-junit-worker";

    private final JdkChecker jdkDetector;
    private final JavaRunner javaRunner;
    private final FrameworkTestRunner frameworkTestRunner;
    private final Supplier<List<Path>> plainJunitWorkerClasspath;
    private final PlainJunitWorkerRunner plainJunitWorkerRunner;
    private final PlainJunitWorkerPoolRunner plainJunitWorkerPoolRunner;
    private final JunitConsoleArguments junitConsoleArguments;
    private final TestRuntimeInputBuilder testRuntimeInputBuilder = new TestRuntimeInputBuilder();
    private final JunitLauncherClasspath junitLauncherClasspath = new JunitLauncherClasspath();
    private final TestConsoleFailureHandler consoleFailureHandler = new TestConsoleFailureHandler();
    private final FrameworkTestSelectionMapper frameworkTestSelectionMapper = new FrameworkTestSelectionMapper();
    private final boolean plainJunitWorkerEnabled;

    public CompiledTestRunner(
            JdkChecker jdkDetector,
            JavaRunner javaRunner,
            FrameworkTestRunner frameworkTestRunner,
            Supplier<List<Path>> plainJunitWorkerClasspath,
            PlainJunitWorkerRunner plainJunitWorkerRunner,
            boolean plainJunitWorkerEnabled,
            String pathSeparator) {
        this(
                jdkDetector,
                javaRunner,
                frameworkTestRunner,
                PlainJunitRunners.legacy(
                        plainJunitWorkerClasspath,
                        plainJunitWorkerRunner),
                plainJunitWorkerEnabled,
                pathSeparator);
    }

    public CompiledTestRunner(
            JdkChecker jdkDetector,
            JavaRunner javaRunner,
            FrameworkTestRunner frameworkTestRunner,
            PlainJunitRunners plainJunitRunners,
            boolean plainJunitWorkerEnabled,
            String pathSeparator) {
        this.jdkDetector = jdkDetector;
        this.javaRunner = javaRunner;
        this.frameworkTestRunner = frameworkTestRunner;
        this.plainJunitWorkerClasspath =
                plainJunitRunners.workerClasspath();
        this.plainJunitWorkerRunner =
                plainJunitRunners.workerRunner();
        this.plainJunitWorkerPoolRunner =
                plainJunitRunners.workerPoolRunner();
        this.junitConsoleArguments = new JunitConsoleArguments(pathSeparator);
        this.plainJunitWorkerEnabled = plainJunitWorkerEnabled;
    }

    TestRunResult run(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            TestCompileResult compileResult,
            TestSelection selection,
            TestWorkerPoolPlan workerPoolPlan,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents,
            TestProfileSettings profileSettings) {
        TestSelection testSelection = selection == null ? TestSelection.empty() : selection;
        TestWorkerPoolPlan testWorkerPoolPlan = workerPoolPlan == null
                ? new TestWorkerPoolPlan(false, 1, List.of())
                : workerPoolPlan;
        TestReportSettings testReportSettings = reportSettings == null ? TestReportSettings.disabled() : reportSettings;
        Optional<Path> reportsDirectory = testReportSettings.absoluteReportsDirectory(projectDirectory);
        TestProfileSettings testProfileSettings = profileSettings == null
                ? TestProfileSettings.disabled()
                : profileSettings;
        Optional<Path> profileDirectory = testProfileSettings.absoluteProfileDirectory(projectDirectory);
        TestRuntimeInputs testRuntime = testRuntimeInputBuilder.build(
                projectDirectory,
                config.build().testRuntime(),
                jvmArguments,
                cliEvents);
        TestJvmArguments testJvmArguments = testRuntime.jvmArguments();
        Map<String, String> testEnvironment = profileEnvironment(
                testRuntime.environment(),
                testProfileSettings,
                projectDirectory,
                config);
        List<Path> runnerClasspath = absolutePaths(TestRuntimeClasspathOrder.compose(
                compileResult.outputDirectory(),
                compileResult.buildResult().outputDirectory(),
                classpaths.test().entries()));
        if (!junitLauncherClasspath.hasConsoleJar(runnerClasspath)) {
            throw new TestRunException(
                    "JUnit Platform Console is not present on the test classpath. "
                            + "Run `zolt resolve` to refresh Zolt's test runner tooling. "
                            + "Keep JUnit, Spock, and other test engines declared in [dependencies.test].");
        }

        JdkStatus jdkStatus = jdkDetector.detect(config.project().java());
        if (!jdkStatus.ok()) {
            throw new BuildException("JDK check failed. " + String.join(" ", jdkStatus.problems()));
        }
        List<Path> launcherClasspath = junitLauncherClasspath.launcherClasspath(runnerClasspath);
        if (frameworkTestRunner.isEnabled(config)) {
            if (testProfileSettings.enabled()) {
                throw new TestRunException("Test profiling is not supported by the "
                        + frameworkTestRunner.testRunnerName()
                        + " runner yet. Run plain JUnit tests through Zolt's JUnit worker or omit --profile-tests.");
            }
            if (reportsDirectory.isPresent()) {
                frameworkTestRunner.unsupportedReportsMessage()
                        .ifPresent(message -> {
                            throw new TestRunException(message);
                        });
            }
            long requestStarted = System.nanoTime();
            FrameworkTestRunResult frameworkResult = frameworkTestRunner.runIfEnabled(new FrameworkTestRunRequest(
                            projectDirectory,
                            config,
                            compileResult.buildResult().outputDirectory(),
                            compileResult.outputDirectory(),
                            runnerClasspath,
                            jdkStatus.java().orElseThrow(),
                            frameworkTestSelectionMapper.map(testSelection),
                            testJvmArguments.values(),
                            testEnvironment))
                    .orElseThrow(() -> new TestRunException(
                            "Framework test runner was not configured for the enabled framework."));
            long requestNanos = System.nanoTime() - requestStarted;
            String output = frameworkResult.output();
            return new TestRunResult(
                    compileResult,
                    output,
                    TestRunResult.metrics(
                            frameworkTestRunner.testRunnerName(),
                            runnerClasspath.size(),
                            frameworkResult.workerClasspathEntries(),
                            frameworkResult.discoveryScanRoots(),
                            -1L,
                            requestNanos),
                    testSelection,
                    testJvmArguments,
                    reportsDirectory,
                    Optional.empty());
        }
        if (plainJunitWorkerEnabled || testProfileSettings.enabled()) {
            List<Path> workerClasspath = plainJunitWorkerClasspath.get();
            List<Path> workerTestRuntimeClasspath =
                    junitLauncherClasspath.workerClasspath(
                            runnerClasspath);
            if (testWorkerPoolPlan.enabled()
                    || plainJunitWorkerPoolRunner.reusesProcesses()) {
                PlainJunitWorkerPoolRunResult poolResult = plainJunitWorkerPoolRunner.run(
                        jdkStatus.java().orElseThrow(),
                        workerClasspath,
                        projectDirectory,
                        config,
                        workerTestRuntimeClasspath,
                        compileResult.outputDirectory().toAbsolutePath().normalize(),
                        testSelection,
                        testWorkerPoolPlan,
                        testJvmArguments,
                        testEnvironment,
                        reportsDirectory,
                        testRuntime.events(),
                        profileDirectory);
                return new TestRunResult(
                        compileResult,
                        poolResult.output(),
                        TestRunResult.metrics(
                                PLAIN_JUNIT_WORKER_RUNNER,
                                runnerClasspath.size(),
                                workerClasspath.size() + runnerClasspath.size(),
                                poolResult.workerRequests(),
                                poolResult.workerStarts(),
                                poolResult.workerRequests(),
                                poolResult.startupNanos(),
                                poolResult.requestNanos()),
                        testSelection,
                        testJvmArguments,
                        reportsDirectory,
                        profileDirectory);
            }
            PlainJunitWorkerRunResult result = plainJunitWorkerRunner.run(
                    jdkStatus.java().orElseThrow(),
                    workerClasspath,
                    projectDirectory,
                    workerTestRuntimeClasspath,
                    compileResult.outputDirectory().toAbsolutePath().normalize(),
                    testSelection,
                    testJvmArguments,
                    testEnvironment,
                    reportsDirectory,
                    testRuntime.events(),
                    profileDirectory);
            if (!result.successful(testSelection)) {
                String summary = profileDirectory
                        .flatMap(directory -> TestProfileSummaryFormatter.format(directory.resolve("profile.json"), testProfileSettings))
                        .map(value -> "\n\n" + value)
                        .orElse("");
                throw new TestRunException(
                        "JUnit worker tests failed with exit code "
                                + result.workerResult().exitCode()
                                + ". Fix failing tests, then run `zolt test` again.\n"
                                + result.workerResult().output().stripTrailing()
                                + summary);
            }
            return new TestRunResult(
                    compileResult,
                    result.workerResult().output(),
                    TestRunResult.metrics(
                            PLAIN_JUNIT_WORKER_RUNNER,
                            runnerClasspath.size(),
                            workerClasspath.size() + runnerClasspath.size(),
                            1,
                            1,
                            1,
                            result.startupNanos(),
                            result.requestNanos()),
                    testSelection,
                    testJvmArguments,
                    reportsDirectory,
                    profileDirectory);
        }
        if (testProfileSettings.enabled()) {
            throw new TestRunException(
                    "Test profiling requires Zolt's JUnit worker. Run with --profile-tests on a plain JUnit project or omit --profile-tests.");
        }
        JavaRunResult result;
        long requestStarted = System.nanoTime();
        try {
            result = javaRunner.run(
                    jdkStatus.java().orElseThrow(),
                    new Classpath(launcherClasspath),
                    CONSOLE_MAIN_CLASS,
                    junitLauncherClasspath.jvmArguments(projectDirectory, runnerClasspath, testJvmArguments),
                    junitConsoleArguments.arguments(
                            runnerClasspath,
                            compileResult.outputDirectory(),
                            testSelection,
                            reportsDirectory,
                            testRuntime.events()),
                    testEnvironment);
        } catch (JavaRunException exception) {
            consoleFailureHandler.throwForFailedRun(exception, testSelection, reportsDirectory);
            throw exception;
        }
        long requestNanos = System.nanoTime() - requestStarted;
        consoleFailureHandler.throwIfLauncherDidNotStart(result.output());
        consoleFailureHandler.throwIfSelectedTestsDidNotMatch(result.output(), testSelection);
        return new TestRunResult(
                compileResult,
                result.output(),
                TestRunResult.metrics(
                        JUNIT_CONSOLE_RUNNER,
                        runnerClasspath.size(),
                        launcherClasspath.size(),
                        1,
                        -1L,
                        requestNanos),
                testSelection,
                testJvmArguments,
                reportsDirectory,
                Optional.empty());
    }

    private static List<Path> absolutePaths(List<Path> classpath) {
        return classpath.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
    }

    private static Map<String, String> profileEnvironment(
            Map<String, String> environment,
            TestProfileSettings settings,
            Path projectDirectory,
            ProjectConfig config) {
        Map<String, String> values = new LinkedHashMap<>(environment);
        if (settings == null || !settings.enabled()) {
            return Map.copyOf(values);
        }
        values.put("ZOLT_TEST_PROFILE_PROJECT_ROOT", projectDirectory.toAbsolutePath().normalize().toString());
        values.put("ZOLT_TEST_PROFILE_PROJECT", config.project().name());
        values.put("ZOLT_TEST_PROFILE_SUITE", settings.suiteName().orElse("all"));
        values.put("ZOLT_TEST_PROFILE_SHARD", settings.shard().orElse(""));
        values.put("ZOLT_TEST_PROFILE_MEMBER", settings.workspaceMember().orElse(""));
        return Map.copyOf(values);
    }
}
