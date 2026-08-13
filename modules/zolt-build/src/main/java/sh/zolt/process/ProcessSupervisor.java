package sh.zolt.process;

import sh.zolt.cancel.BuildCancellation;
import sh.zolt.cancel.ProcessCancellation;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Launches, streams, bounds, waits for, and terminates one child process and its descendants. */
public final class ProcessSupervisor {
    public static final int DEFAULT_DIAGNOSTIC_TAIL_CHARACTERS = 64 * 1024;
    private static final long PUMP_JOIN_MILLIS = 1000L;

    public SupervisedProcessResult run(SupervisedProcessSpec spec) throws IOException, InterruptedException {
        Process process = start(spec);
        AtomicBoolean terminationInitiated = new AtomicBoolean();
        BoundedProcessOutput output = new BoundedProcessOutput(spec.diagnosticTailCharacters());
        AtomicReference<Throwable> streamFailure = new AtomicReference<>();
        List<Pump> pumps = startPumps(process, spec, output, streamFailure);
        boolean timedOut = false;
        try (BuildCancellation.Registration ignored = ProcessCancellation.register(
                process,
                () -> terminationInitiated.set(true))) {
            try {
                if (!waitFor(process, spec.timeout())) {
                    timedOut = true;
                    terminationInitiated.set(true);
                    ProcessCancellation.terminate(process);
                    process.waitFor();
                }
            } catch (InterruptedException exception) {
                terminationInitiated.set(true);
                ProcessCancellation.terminate(process);
                throw exception;
            } finally {
                finishPumps(pumps);
            }
        }
        throwIfStreamFailed(streamFailure.get(), terminationInitiated.get());
        int exitCode = process.exitValue();
        return new SupervisedProcessResult(
                exitCode,
                output.tail(),
                output.endedWithNewline(),
                timedOut,
                terminationInitiated.get(),
                ProcessExitInterpreter.signal(exitCode));
    }

    private static Process start(SupervisedProcessSpec spec) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(spec.command())
                .redirectErrorStream(spec.mergeErrorStream());
        if (spec.directory() != null) {
            builder.directory(spec.directory().toFile());
        }
        if (spec.clearEnvironment()) {
            builder.environment().clear();
        }
        builder.environment().putAll(spec.environment());
        if (spec.inputPolicy() == ProcessInputPolicy.INHERIT) {
            builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        }
        Process process = builder.start();
        if (spec.inputPolicy() == ProcessInputPolicy.CLOSED) {
            process.getOutputStream().close();
        }
        return process;
    }

    private static List<Pump> startPumps(
            Process process,
            SupervisedProcessSpec spec,
            BoundedProcessOutput output,
            AtomicReference<Throwable> streamFailure) {
        List<Pump> pumps = new ArrayList<>();
        pumps.add(startPump(process, process.getInputStream(), spec.stdoutConsumer(), output, streamFailure));
        if (!spec.mergeErrorStream()) {
            pumps.add(startPump(process, process.getErrorStream(), spec.stderrConsumer(), output, streamFailure));
        }
        return List.copyOf(pumps);
    }

    private static Pump startPump(
            Process process,
            InputStream input,
            Consumer<String> consumer,
            BoundedProcessOutput output,
            AtomicReference<Throwable> streamFailure) {
        AtomicBoolean closedBySupervisor = new AtomicBoolean();
        Thread thread = new Thread(() -> pump(
                process,
                input,
                consumer,
                output,
                streamFailure,
                closedBySupervisor));
        thread.setName("zolt-process-output-" + process.pid());
        thread.setDaemon(true);
        thread.start();
        return new Pump(thread, input, closedBySupervisor);
    }

    private static void pump(
            Process process,
            InputStream input,
            Consumer<String> consumer,
            BoundedProcessOutput output,
            AtomicReference<Throwable> streamFailure,
            AtomicBoolean closedBySupervisor) {
        try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                String chunk = new String(buffer, 0, read);
                output.append(chunk);
                consumer.accept(chunk);
            }
        } catch (IOException | RuntimeException exception) {
            if (!closedBySupervisor.get()
                    && streamFailure.compareAndSet(null, exception)
                    && process.isAlive()) {
                ProcessCancellation.terminate(process);
            }
        }
    }

    private static boolean waitFor(Process process, Duration timeout) throws InterruptedException {
        if (timeout == null) {
            process.waitFor();
            return true;
        }
        long timeoutMillis = Math.max(1L, timeout.toMillis());
        return process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private static void finishPumps(List<Pump> pumps) throws InterruptedException {
        for (Pump pump : pumps) {
            pump.thread().join(PUMP_JOIN_MILLIS);
        }
        for (Pump pump : pumps) {
            if (pump.thread().isAlive()) {
                pump.closedBySupervisor().set(true);
                closeQuietly(pump.input());
            }
        }
        for (Pump pump : pumps) {
            if (pump.thread().isAlive()) {
                pump.thread().join(PUMP_JOIN_MILLIS);
            }
        }
    }

    private static void throwIfStreamFailed(Throwable failure, boolean terminated) throws IOException {
        if (failure == null || terminated) {
            return;
        }
        if (failure instanceof IOException ioException) {
            throw ioException;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new IOException("Could not stream process output.", failure);
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // Closing a retained descendant pipe is best-effort after the immediate process exits.
        }
    }

    private record Pump(
            Thread thread,
            InputStream input,
            AtomicBoolean closedBySupervisor) {
    }
}
