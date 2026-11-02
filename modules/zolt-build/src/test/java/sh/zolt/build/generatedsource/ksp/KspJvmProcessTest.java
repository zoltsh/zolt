package sh.zolt.build.generatedsource.ksp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildException;
import sh.zolt.process.ProcessInputPolicy;
import sh.zolt.process.SupervisedProcessResult;
import sh.zolt.process.SupervisedProcessSpec;

final class KspJvmProcessTest {
    @TempDir
    private Path projectDirectory;

    @AfterEach
    void clearInterruptedStatus() {
        Thread.interrupted();
    }

    @Test
    void launchesWithClosedInputClearedEnvironmentAndBoundedDiagnostics() {
        AtomicReference<SupervisedProcessSpec> captured = new AtomicReference<>();
        KspJvmProcess process = new KspJvmProcess(Duration.ofSeconds(30), spec -> {
            captured.set(spec);
            return result(0, "done", false);
        });

        process.run(List.of("/jdk/bin/java", "KspMain"), projectDirectory, subject());

        SupervisedProcessSpec spec = captured.get();
        assertEquals(List.of("/jdk/bin/java", "KspMain"), spec.command());
        assertEquals(projectDirectory, spec.directory());
        assertTrue(spec.clearEnvironment());
        assertEquals(ProcessInputPolicy.CLOSED, spec.inputPolicy());
        assertEquals(Duration.ofSeconds(30), spec.timeout());
        assertTrue(spec.mergeErrorStream());
        assertTrue(spec.environment().isEmpty());
    }

    @Test
    void reportsNonzeroExitWithTheBoundedDiagnosticTail() {
        KspJvmProcess process = new KspJvmProcess(
                Duration.ofSeconds(30), ignored -> result(3, "processor failed\n", false));

        BuildException exception = assertThrows(
                BuildException.class,
                () -> process.run(List.of("java"), projectDirectory, subject()));

        assertTrue(exception.getMessage().contains("failed with exit code 3"));
        assertTrue(exception.getMessage().contains("processor failed"));
        assertTrue(exception.getMessage().contains("Fix the processor diagnostics"));
    }

    @Test
    void reportsTimeoutBeforeTheTerminatedExitCode() {
        KspJvmProcess process = new KspJvmProcess(
                Duration.ofSeconds(30), ignored -> result(143, "terminated", true));

        BuildException exception = assertThrows(
                BuildException.class,
                () -> process.run(List.of("java"), projectDirectory, subject()));

        assertTrue(exception.getMessage().contains("exceeded 30 seconds"));
        assertFalse(exception.getMessage().contains("exit code 143"));
    }

    @Test
    void wrapsLaunchFailureAndPreservesInterruption() {
        IOException launchFailure = new IOException("missing java");
        KspJvmProcess launch = new KspJvmProcess(Duration.ofSeconds(30), ignored -> {
            throw launchFailure;
        });

        BuildException launchException = assertThrows(
                BuildException.class,
                () -> launch.run(List.of("java"), projectDirectory, subject()));
        assertSame(launchFailure, launchException.getCause());

        KspJvmProcess interrupted = new KspJvmProcess(Duration.ofSeconds(30), ignored -> {
            throw new InterruptedException("stop");
        });
        assertThrows(
                BuildException.class,
                () -> interrupted.run(List.of("java"), projectDirectory, subject()));
        assertTrue(Thread.currentThread().isInterrupted());
    }

    private static SupervisedProcessResult result(
            int exitCode,
            String diagnostics,
            boolean timedOut) {
        return new SupervisedProcessResult(
                exitCode,
                diagnostics,
                diagnostics.endsWith("\n"),
                timedOut,
                timedOut,
                -1);
    }

    private static String subject() {
        return "[generated.main.symbols]";
    }
}
