package sh.zolt.build.generatedsource;

import sh.zolt.build.BuildException;
import sh.zolt.build.generatedsource.ExecGeneratedSourceService.ProcessResult;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import sh.zolt.process.ProcessInputPolicy;
import sh.zolt.process.ProcessSupervisor;
import sh.zolt.process.SupervisedProcessResult;
import sh.zolt.process.SupervisedProcessSpec;

/**
 * The production exec {@link ExecGeneratedSourceService.ProcessRunner}: launches a subprocess in the
 * given directory with a cleared, curated environment and delegates bounded output, timeout, and
 * process-tree termination semantics to the shared supervisor.
 */
final class ExecSubprocess {
    private ExecSubprocess() {
    }

    static ProcessResult run(List<String> command, Path directory, Map<String, String> environment, Duration timeout) {
        try {
            SupervisedProcessResult result = new ProcessSupervisor().run(
                    SupervisedProcessSpec.builder(command)
                            .directory(directory)
                            .environment(environment)
                            .clearEnvironment(true)
                            .inputPolicy(ProcessInputPolicy.CLOSED)
                            .timeout(timeout)
                            .build());
            return new ProcessResult(
                    result.exitCode(),
                    result.diagnosticTail(),
                    result.timedOut());
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not run exec tool. Check that the configured tool can launch processes.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BuildException("Exec generation was interrupted. Try `zolt build` again.", exception);
        }
    }

}
