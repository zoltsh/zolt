package sh.zolt.build.generatedsource.ksp;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import sh.zolt.build.BuildException;
import sh.zolt.process.ProcessInputPolicy;
import sh.zolt.process.ProcessSupervisor;
import sh.zolt.process.SupervisedProcessResult;
import sh.zolt.process.SupervisedProcessSpec;

/** Runs one bounded, environment-isolated standalone KSP2 JVM command. */
final class KspJvmProcess {
    static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    private final Duration timeout;
    private final ProcessRunner runner;

    KspJvmProcess() {
        this(DEFAULT_TIMEOUT, new ProcessSupervisor()::run);
    }

    KspJvmProcess(Duration timeout, ProcessRunner runner) {
        this.timeout = Objects.requireNonNull(timeout, "KSP process timeout is required.");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("KSP process timeout must be positive.");
        }
        this.runner = Objects.requireNonNull(runner, "KSP process runner is required.");
    }

    void run(List<String> command, Path projectRoot, String subject) {
        SupervisedProcessResult result;
        try {
            result = runner.run(spec(command, projectRoot, timeout));
        } catch (IOException exception) {
            throw BuildException.actionable(
                    "Could not launch KSP generation for " + subject + ".",
                    "Check that the selected JDK and locked KSP artifacts are readable, then retry.",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BuildException(
                    "KSP generation for " + subject + " was interrupted. Retry the command.",
                    exception);
        }
        if (result.timedOut()) {
            throw BuildException.actionable(
                    "KSP generation for " + subject + " exceeded " + timeout.toSeconds()
                            + " seconds and was terminated.",
                    "Fix the processor so it completes within the bounded run or reduce its input scope.");
        }
        if (result.exitCode() != 0) {
            String diagnostics = result.diagnosticTail().stripTrailing();
            throw BuildException.actionable(
                    "KSP generation for " + subject + " failed with exit code "
                            + result.exitCode() + (diagnostics.isEmpty() ? "." : ".\n" + diagnostics),
                    "Fix the processor diagnostics, then retry the build.");
        }
    }

    static SupervisedProcessSpec spec(
            List<String> command,
            Path projectRoot,
            Duration timeout) {
        return SupervisedProcessSpec.builder(command)
                .directory(projectRoot)
                .environment(Map.of())
                .clearEnvironment(true)
                .inputPolicy(ProcessInputPolicy.CLOSED)
                .timeout(timeout)
                .build();
    }

    @FunctionalInterface
    interface ProcessRunner {
        SupervisedProcessResult run(SupervisedProcessSpec spec)
                throws IOException, InterruptedException;
    }
}
