package sh.zolt.build.generatedsource;

import sh.zolt.build.BuildException;
import sh.zolt.cancel.BuildCancellation;
import sh.zolt.cancel.ProcessCancellation;
import sh.zolt.build.generatedsource.ExecGeneratedSourceService.ProcessResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The production exec {@link ExecGeneratedSourceService.ProcessRunner}: launches a subprocess in the
 * given directory with a cleared, curated environment, drains its merged stdout/stderr on a pump thread
 * (so a chatty tool cannot deadlock on a full pipe), and enforces the step timeout by destroying the
 * process (SIGTERM, then {@code destroyForcibly} after a short grace).
 */
final class ExecSubprocess {
    private static final int DESTROY_GRACE_SECONDS = 3;

    private ExecSubprocess() {
    }

    static ProcessResult run(List<String> command, Path directory, Map<String, String> environment, Duration timeout) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true);
            processBuilder.environment().clear();
            processBuilder.environment().putAll(environment);
            Process process = processBuilder.start();
            try (BuildCancellation.Registration ignored =
                    ProcessCancellation.register(process)) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                Thread pump = new Thread(() -> pump(process, buffer));
                pump.setDaemon(true);
                pump.start();
                boolean finished = process.waitFor(Math.max(1L, timeout.toSeconds()), TimeUnit.SECONDS);
                if (!finished) {
                    process.destroy();
                    if (!process.waitFor(DESTROY_GRACE_SECONDS, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                    process.waitFor();
                    joinQuietly(pump);
                    return new ProcessResult(-1, buffer.toString(StandardCharsets.UTF_8), true);
                }
                joinQuietly(pump);
                return new ProcessResult(process.exitValue(), buffer.toString(StandardCharsets.UTF_8), false);
            }
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not run exec tool. Check that the configured tool can launch processes.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BuildException("Exec generation was interrupted. Try `zolt build` again.", exception);
        }
    }

    private static void pump(Process process, ByteArrayOutputStream buffer) {
        try (InputStream in = process.getInputStream()) {
            in.transferTo(buffer);
        } catch (IOException ignored) {
            // process terminated; whatever was buffered is the log tail.
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(1000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
