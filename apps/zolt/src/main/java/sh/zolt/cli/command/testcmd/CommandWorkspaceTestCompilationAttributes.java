package sh.zolt.cli.command.testcmd;

import sh.zolt.build.BuildResult;
import sh.zolt.build.testruntime.TestRunResult;
import sh.zolt.build.testruntime.compile.TestCompileResult;
import sh.zolt.cli.command.CommandAttributeKeys;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.test.WorkspaceTestCompileResult;
import sh.zolt.workspace.test.WorkspaceTestResult;
import java.util.List;
import java.util.Map;

final class CommandWorkspaceTestCompilationAttributes {
    private CommandWorkspaceTestCompilationAttributes() {
    }

    static void add(Map<String, String> attributes, WorkspaceTestResult result) {
        addMain(
                attributes,
                result.builtMembers(),
                result.mainCompilationSkippedCount(),
                result.mainCompilationExecutedCount());
        addTest(
                attributes,
                result.testCompilationSkippedCount(),
                testCompilationRestoredCount(result),
                result.testCompilationExecutedCount());
    }

    static void add(Map<String, String> attributes, WorkspaceTestCompileResult result) {
        addMain(
                attributes,
                result.builtMembers(),
                result.mainCompilationSkippedCount(),
                result.mainCompilationExecutedCount());
        addTest(
                attributes,
                result.testCompilationSkippedCount(),
                testCompilationRestoredCount(result),
                result.testCompilationExecutedCount());
    }

    private static void addMain(
            Map<String, String> attributes,
            List<WorkspaceBuildResult.MemberBuildResult> builtMembers,
            int skipped,
            int executed) {
        attributes.put(CommandAttributeKeys.MAIN_COMPILATIONS_SKIPPED, Integer.toString(skipped));
        attributes.put(
                CommandAttributeKeys.MAIN_COMPILATIONS_RESTORED,
                Integer.toString(mainCompilationRestoredCount(builtMembers)));
        attributes.put(CommandAttributeKeys.MAIN_COMPILATIONS_EXECUTED, Integer.toString(executed));
        attributes.put(
                CommandAttributeKeys.MAIN_RESTORED_CLASSES,
                Integer.toString(mainRestoredClassCount(builtMembers)));
    }

    private static void addTest(
            Map<String, String> attributes,
            int skipped,
            int restored,
            int executed) {
        attributes.put(CommandAttributeKeys.TEST_COMPILATIONS_SKIPPED, Integer.toString(skipped));
        attributes.put(CommandAttributeKeys.TEST_COMPILATIONS_RESTORED, Integer.toString(restored));
        attributes.put(CommandAttributeKeys.TEST_COMPILATIONS_EXECUTED, Integer.toString(executed));
    }

    private static int mainCompilationRestoredCount(
            List<WorkspaceBuildResult.MemberBuildResult> builtMembers) {
        return (int) builtMembers.stream()
                .map(WorkspaceBuildResult.MemberBuildResult::result)
                .filter(BuildResult::mainCompilationRestored)
                .count();
    }

    private static int mainRestoredClassCount(
            List<WorkspaceBuildResult.MemberBuildResult> builtMembers) {
        return builtMembers.stream()
                .map(WorkspaceBuildResult.MemberBuildResult::result)
                .mapToInt(BuildResult::mainRestoredClassCount)
                .sum();
    }

    private static int testCompilationRestoredCount(WorkspaceTestResult result) {
        return (int) result.members().stream()
                .map(WorkspaceTestResult.MemberTestRunResult::result)
                .map(TestRunResult::compileResult)
                .filter(TestCompileResult::testCompilationRestored)
                .count();
    }

    private static int testCompilationRestoredCount(WorkspaceTestCompileResult result) {
        return (int) result.members().stream()
                .map(WorkspaceTestCompileResult.MemberTestCompileResult::result)
                .filter(TestCompileResult::testCompilationRestored)
                .count();
    }
}
