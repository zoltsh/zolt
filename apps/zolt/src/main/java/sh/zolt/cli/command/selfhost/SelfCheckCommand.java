package sh.zolt.cli.command.selfhost;

import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.ZoltCli;
import sh.zolt.cli.command.CommandAttributeKeys;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.command.CommandTimings;
import sh.zolt.perf.TimingRecorder;
import sh.zolt.selfhost.SelfCheckResult;
import sh.zolt.selfhost.SelfCheckService;
import java.nio.file.Path;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine;

@Command(
        name = "self-check",
        description = "Run the self-hosting verification path.",
        hidden = true)
public final class SelfCheckCommand implements Runnable {
    private final SelfCheckService selfCheckService;

    @Option(names = "--offline", description = "Use only artifacts already present in the local cache.")
    private boolean offline;

    @Option(names = "--native", description = "Also build and smoke the Native Image binary.")
    private boolean nativeCheck;

    @Option(names = "--native-image", description = "Path to the native-image executable.")
    private Path nativeImageExecutable;

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Option(names = "--cache-root", hidden = true)
    private Path cacheRoot = LocalArtifactCache.defaultRoot();

    @Mixin
    private ZoltCli.TimingOptions timingOptions = new ZoltCli.TimingOptions();

    @Spec
    private CommandSpec spec;

    public SelfCheckCommand() {
        this(new SelfCheckService());
    }

    SelfCheckCommand(SelfCheckService selfCheckService) {
        this.selfCheckService = selfCheckService;
    }

    @Override
    public void run() {
        TimingRecorder timings = CommandTimings.recorder(timingOptions);
        Path projectRoot = projectDirectory.path();
        try {
            SelfCheckResult result = timings.measure(
                    "self-check",
                    () -> selfCheckService.check(
                            projectRoot,
                            cacheRoot,
                            offline,
                            nativeCheck,
                            nativeImageExecutable),
                    SelfCheckCommand::selfCheckAttributes);
            printSelfCheckStatus(result);
            if (!result.ok()) {
                throw new CommandLine.ExecutionException(spec.commandLine(), "Self-check failed.");
            }
        } finally {
            CommandTimings.print(spec, "self-check", projectRoot, timingOptions, timings);
        }
    }

    private static Map<String, String> selfCheckAttributes(SelfCheckResult result) {
        long failedSteps = result.steps().stream().filter(step -> !step.ok()).count();
        return Map.of(
                CommandAttributeKeys.STEPS, Integer.toString(result.steps().size()),
                CommandAttributeKeys.FAILED_STEPS, Long.toString(failedSteps),
                CommandAttributeKeys.OK, Boolean.toString(result.ok()));
    }

    private void printSelfCheckStatus(SelfCheckResult result) {
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        output.status("Self-check status", result.ok() ? "ok" : "error");
        for (SelfCheckResult.SelfCheckStep step : result.steps()) {
            String marker = step.ok() ? "ok" : "error";
            output.statusDetail(marker, step.name() + " - " + step.message());
        }
    }
}
