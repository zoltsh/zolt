package sh.zolt.cli.command.testcmd;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.test.WorkspaceTestCompileResult;
import sh.zolt.workspace.test.WorkspaceTestResult;
import java.util.List;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Prints each member's captured test output.
 *
 * <p>Members run concurrently but their output is buffered until the whole run finishes, so this
 * replays it in selection order however the pool interleaved.
 */
final class WorkspaceTestCommandOutput {
    private WorkspaceTestCommandOutput() {
    }

    static void printMembers(
            CommandSpec spec,
            CommandHumanOutput output,
            WorkspaceTestResult result) {
        printMainRestores(output, result.builtMembers());
        for (WorkspaceTestResult.MemberTestRunResult member : result.members()) {
            if (member.result().compileResult().testCompilationRestored()) {
                printTestRestore(output, member.member());
            }
            String memberOutput = member.result().output();
            CommandOutput.printAndFlush(spec, memberOutput);
            if (!memberOutput.isEmpty() && !memberOutput.endsWith("\n")) {
                output.blankLine();
            }
            output.success("Tests passed in " + member.member());
            member.result().reportsDirectory().ifPresent(directory ->
                    output.pointer("wrote", directory.toString()));
        }
    }

    static void printCompileMembers(
            CommandHumanOutput output,
            WorkspaceTestCompileResult result) {
        printMainRestores(output, result.builtMembers());
        for (WorkspaceTestCompileResult.MemberTestCompileResult member : result.members()) {
            if (member.result().testCompilationSkipped()) {
                output.detail("Skipped test compilation in " + member.member()
                        + "; inputs are unchanged");
            } else if (member.result().testCompilationRestored()) {
                printTestRestore(output, member.member());
            } else {
                output.success("Tests compiled in " + member.member());
            }
        }
    }

    private static void printMainRestores(
            CommandHumanOutput output,
            List<WorkspaceBuildResult.MemberBuildResult> builtMembers) {
        builtMembers.stream()
                .filter(member -> member.result().mainCompilationRestored())
                .forEach(member -> output.detail(
                        "Restored "
                                + member.result().mainRestoredClassCount()
                                + " main classes in "
                                + member.member()
                                + " (build cache)"));
    }

    private static void printTestRestore(CommandHumanOutput output, String member) {
        output.detail("Restored test classes in " + member + " (build cache)");
    }
}
