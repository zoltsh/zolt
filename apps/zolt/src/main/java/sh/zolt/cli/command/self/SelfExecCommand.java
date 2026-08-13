package sh.zolt.cli.command.self;

import sh.zolt.cli.command.CommandFailures;
import sh.zolt.release.update.NativeUpdateException;
import sh.zolt.release.update.NativeVersionExecPlan;
import sh.zolt.release.update.NativeVersionExecRequest;
import sh.zolt.release.update.NativeVersionExecService;
import sh.zolt.process.ProcessInputPolicy;
import sh.zolt.process.ProcessSupervisor;
import sh.zolt.process.SupervisedProcessSpec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "exec", description = "Run a command with an installed native Zolt version.")
public final class SelfExecCommand extends SelfCommand.NativeSelfOptions implements Callable<Integer> {
    private final NativeVersionExecService execService;

    @Parameters(index = "0", paramLabel = "<VERSION>", description = "Installed version to run.")
    private String version;

    @Parameters(index = "1..*", arity = "1..*", paramLabel = "<ARGS>", description = "Command after -- to run.")
    private List<String> arguments;

    @Spec
    private CommandSpec spec;

    public SelfExecCommand() {
        this(new NativeVersionExecService());
    }

    SelfExecCommand(NativeVersionExecService execService) {
        this.execService = execService;
    }

    @Override
    public Integer call() {
        try {
            NativeVersionExecPlan plan = execService.plan(new NativeVersionExecRequest(
                    installRoot(),
                    currentExecutable(),
                    version,
                    arguments));
            return run(plan);
        } catch (IOException | NativeUpdateException exception) {
            throw CommandFailures.user(spec, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw CommandFailures.user(spec, new NativeUpdateException("Native Zolt exec was interrupted.", exception));
        }
    }

    private int run(NativeVersionExecPlan plan) throws IOException, InterruptedException {
        ArrayList<String> command = new ArrayList<>();
        command.add(plan.executable().toString());
        command.addAll(plan.arguments());
        return new ProcessSupervisor().run(
                        SupervisedProcessSpec.builder(command)
                                .mergeErrorStream(false)
                                .inputPolicy(ProcessInputPolicy.INHERIT)
                                .stdoutConsumer(chunk -> write(spec.commandLine().getOut(), chunk))
                                .stderrConsumer(chunk -> write(spec.commandLine().getErr(), chunk))
                                .build())
                .exitCode();
    }

    private static void write(java.io.PrintWriter writer, String chunk) {
        writer.write(chunk);
        writer.flush();
    }
}
