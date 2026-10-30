package sh.zolt.build.testruntime;

import sh.zolt.classpath.ClasspathSet;
import sh.zolt.build.profile.TestProfileSettings;
import sh.zolt.build.testruntime.compile.TestCompileResult;
import sh.zolt.build.testruntime.execution.CompiledTestExecutionRunner;
import sh.zolt.project.ProjectConfig;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.shard.TestShardSpec;
import sh.zolt.test.TestSelection;
import java.nio.file.Path;
import java.util.List;

/**
 * The "run already-compiled tests" surface: the telescoping default overloads over
 * {@link CompiledTestExecutionRunner#run}, extracted from {@link TestRunService} so the service composes
 * compilation and execution while this collaborator owns the runtime-option defaults.
 */
final class CompiledTestRunInvoker {
    private final CompiledTestExecutionRunner compiledTestExecutionRunner;

    CompiledTestRunInvoker(CompiledTestExecutionRunner compiledTestExecutionRunner) {
        this.compiledTestExecutionRunner = compiledTestExecutionRunner;
    }

    TestRunResult runCompiledTests(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            TestCompileResult compileResult) {
        return runCompiledTests(projectDirectory, config, classpaths, compileResult, TestSelection.empty());
    }

    TestRunResult runCompiledTests(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            TestCompileResult compileResult,
            TestSelection selection) {
        return runCompiledTests(projectDirectory, config, classpaths, compileResult, selection, TestJvmArguments.empty());
    }

    TestRunResult runCompiledTests(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            TestCompileResult compileResult,
            TestSelection selection,
            TestJvmArguments jvmArguments) {
        return runCompiledTests(projectDirectory, config, classpaths, compileResult, selection, jvmArguments, TestReportSettings.disabled());
    }

    TestRunResult runCompiledTests(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            TestCompileResult compileResult,
            TestSelection selection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings) {
        return runCompiledTests(projectDirectory, config, classpaths, compileResult, selection, jvmArguments, reportSettings, List.of());
    }

    TestRunResult runCompiledTests(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            TestCompileResult compileResult,
            TestSelection selection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents) {
        return runCompiledTests(projectDirectory, config, classpaths, compileResult, selection, jvmArguments, reportSettings, cliEvents, "all");
    }

    TestRunResult runCompiledTests(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            TestCompileResult compileResult,
            TestSelection selection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents,
            String suiteName) {
        return runCompiledTests(projectDirectory, config, classpaths, compileResult, selection, jvmArguments, reportSettings, cliEvents, suiteName, null);
    }

    TestRunResult runCompiledTests(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            TestCompileResult compileResult,
            TestSelection selection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents,
            String suiteName,
            TestShardSpec shard) {
        return runCompiledTests(
                projectDirectory,
                config,
                classpaths,
                compileResult,
                selection,
                jvmArguments,
                reportSettings,
                cliEvents,
                suiteName,
                shard,
                TestProfileSettings.disabled());
    }

    TestRunResult runCompiledTests(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            TestCompileResult compileResult,
            TestSelection selection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents,
            String suiteName,
            TestShardSpec shard,
            TestProfileSettings profileSettings) {
        try (sh.zolt.build.ProjectMutationLock ignored =
                sh.zolt.build.ProjectMutationLock.acquire(projectDirectory)) {
            return compiledTestExecutionRunner.run(
                    projectDirectory,
                    config,
                    classpaths,
                    compileResult,
                    selection,
                    jvmArguments,
                    reportSettings,
                    cliEvents,
                    suiteName,
                    shard,
                    profileSettings);
        }
    }
}
