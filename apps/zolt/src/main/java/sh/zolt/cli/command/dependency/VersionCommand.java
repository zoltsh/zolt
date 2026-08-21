package sh.zolt.cli.command.dependency;

import sh.zolt.cli.ZoltCli;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Reports the installed Zolt version and nothing else.
 *
 * <p>Version aliases live under {@code zolt versions} (design §20); the singular noun is reserved
 * for the tool's own version so the two never read as the same command family.
 */
@Command(name = "version", description = "Print the Zolt version.")
public final class VersionCommand implements Runnable {
    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().getOut().println(ZoltCli.version());
    }
}
