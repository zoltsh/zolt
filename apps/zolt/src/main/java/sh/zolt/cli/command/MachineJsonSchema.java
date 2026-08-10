package sh.zolt.cli.command;

import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParseResult;

/** Selects the valid machine schema requested by a command without trusting invalid values. */
public final class MachineJsonSchema {
    private MachineJsonSchema() {
    }

    public static int selected(CommandSpec spec) {
        ParseResult parseResult = spec.commandLine().getParseResult();
        if (parseResult == null || !parseResult.hasMatchedOption("--schema-version")) {
            return 1;
        }
        Object value = parseResult.matchedOptionValue("--schema-version", "1");
        return value != null && "2".equals(value.toString()) ? 2 : 1;
    }
}
