package sh.zolt.cli.command.testcmd;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.workspace.test.WorkspaceTestResult;
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
        for (WorkspaceTestResult.MemberTestRunResult member : result.members()) {
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
}
