package sh.zolt.cli.command;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.resolve.ResolveResult;
import picocli.CommandLine.Model.CommandSpec;

public final class CommandResolveOutput {
    private CommandResolveOutput() {
    }

    public static void print(CommandSpec spec, ResolveResult result) {
        print(spec, result, true);
    }

    public static void print(CommandSpec spec, ResolveResult result, boolean wroteLockfile) {
        print(spec, result, wroteLockfile ? "wrote" : "verified");
    }

    /**
     * A workspace resolve names what it actually did: verified a locked run, left a lock that was
     * already current alone, or wrote a new one.
     */
    public static void printWorkspace(
            CommandSpec spec, ResolveResult result, boolean locked, boolean resolutionSkipped) {
        if (locked) {
            print(spec, result, "verified");
            return;
        }
        print(spec, result, resolutionSkipped ? "up to date" : "wrote");
    }

    private static void print(CommandSpec spec, ResolveResult result, String verb) {
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        output.summary(
                "Resolved " + result.resolvedCount() + " packages",
                result.downloadCount() + " downloaded",
                result.conflictCount() + " conflicts");
        output.pointer(verb, result.lockfilePath().toString());
    }
}
