package sh.zolt.cli.command.testcmd;

import sh.zolt.build.profile.TestProfileSettings;
import sh.zolt.build.testruntime.TestReportSettings;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.shard.TestShardSpec;
import sh.zolt.test.TestSelection;
import sh.zolt.workspace.service.WorkspaceTestConcurrency;
import java.util.List;

/**
 * The parsed {@code zolt test} inputs a run needs: what to select, how to launch, where to report, and
 * which suite/shard/profile to use. Grouped so the workspace and single-project run flows take one
 * request value instead of a wide parameter list.
 */
record TestCommandRequest(
        TestSelection testSelection,
        TestJvmArguments testJvmArguments,
        TestReportSettings reportSettings,
        TestProfileSettings profileSettings,
        List<String> requestedTestEvents,
        String suiteName,
        TestShardSpec shard,
        WorkspaceTestConcurrency concurrency) {
}
