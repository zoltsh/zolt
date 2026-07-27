package sh.zolt.build.testruntime;

import sh.zolt.build.BuildResultWithClasspaths;
import sh.zolt.project.ProjectConfig;
import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.shard.TestShardSpec;
import java.nio.file.Path;
import java.util.List;

/** Compiles tests from a prebuilt result and runs them, preserving its resolved package model. */
final class TestRunFromBuildResult {
    private TestRunFromBuildResult() {
    }

    static TestRunResult run(
            TestRunService service,
            Path projectDirectory,
            ProjectConfig config,
            BuildResultWithClasspaths buildResult,
            TestSelection selection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents,
            String suiteName,
            TestShardSpec shard) {
        return service.runCompiledTests(
                projectDirectory,
                config,
                buildResult.classpaths(),
                service.compileTests(projectDirectory, config, buildResult),
                selection,
                jvmArguments,
                reportSettings,
                cliEvents,
                suiteName,
                shard);
    }
}
