package sh.zolt.build.testruntime;

import sh.zolt.build.profile.TestProfileSettings;
import sh.zolt.build.testruntime.compile.TestCompileResult;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.project.ProjectConfig;
import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.shard.TestShardSpec;
import java.nio.file.Path;
import java.util.List;

/** Public compiled-test execution overloads shared by the test-run service facade. */
class CompiledTestRunService {
    private final CompiledTestRunInvoker compiledTestRunInvoker;

    CompiledTestRunService(
            CompiledTestRunInvoker compiledTestRunInvoker) {
        this.compiledTestRunInvoker = compiledTestRunInvoker;
    }

    final CompiledTestRunInvoker compiledTestRunInvoker() {
        return compiledTestRunInvoker;
    }

    public TestRunResult runCompiledTests(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            TestCompileResult compileResult) {
        return compiledTestRunInvoker.runCompiledTests(
                projectDirectory,
                config,
                classpaths,
                compileResult);
    }

    public TestRunResult runCompiledTests(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            TestCompileResult compileResult,
            TestSelection selection) {
        return compiledTestRunInvoker.runCompiledTests(
                projectDirectory,
                config,
                classpaths,
                compileResult,
                selection);
    }

    public TestRunResult runCompiledTests(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            TestCompileResult compileResult,
            TestSelection selection,
            TestJvmArguments jvmArguments) {
        return compiledTestRunInvoker.runCompiledTests(
                projectDirectory,
                config,
                classpaths,
                compileResult,
                selection,
                jvmArguments);
    }

    public TestRunResult runCompiledTests(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            TestCompileResult compileResult,
            TestSelection selection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings) {
        return compiledTestRunInvoker.runCompiledTests(
                projectDirectory,
                config,
                classpaths,
                compileResult,
                selection,
                jvmArguments,
                reportSettings);
    }

    public TestRunResult runCompiledTests(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            TestCompileResult compileResult,
            TestSelection selection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents) {
        return compiledTestRunInvoker.runCompiledTests(
                projectDirectory,
                config,
                classpaths,
                compileResult,
                selection,
                jvmArguments,
                reportSettings,
                cliEvents);
    }

    public TestRunResult runCompiledTests(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            TestCompileResult compileResult,
            TestSelection selection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents,
            String suiteName) {
        return compiledTestRunInvoker.runCompiledTests(
                projectDirectory,
                config,
                classpaths,
                compileResult,
                selection,
                jvmArguments,
                reportSettings,
                cliEvents,
                suiteName);
    }

    public TestRunResult runCompiledTests(
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
        return compiledTestRunInvoker.runCompiledTests(
                projectDirectory,
                config,
                classpaths,
                compileResult,
                selection,
                jvmArguments,
                reportSettings,
                cliEvents,
                suiteName,
                shard);
    }

    public TestRunResult runCompiledTests(
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
        return compiledTestRunInvoker.runCompiledTests(
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
