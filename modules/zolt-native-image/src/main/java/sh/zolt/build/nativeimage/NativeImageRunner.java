package sh.zolt.build.nativeimage;

import sh.zolt.build.NativeImageException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public final class NativeImageRunner {
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(60);

    private final String pathSeparator;
    private final ProcessLauncher processLauncher;

    public NativeImageRunner() {
        this(
                java.io.File.pathSeparator,
                (command, progress) -> runProcess(command, progress, HEARTBEAT_INTERVAL));
    }

    NativeImageRunner(String pathSeparator, ProcessRunner processRunner) {
        this(pathSeparator, (command, progress) -> processRunner.run(command));
    }

    NativeImageRunner(String pathSeparator, ProcessLauncher processLauncher) {
        this.pathSeparator = pathSeparator;
        this.processLauncher = processLauncher;
    }

    public NativeImageResult build(NativeImageRequest request) {
        return build(request, () -> {
        });
    }

    public NativeImageResult build(NativeImageRequest request, Runnable progress) {
        validate(request);
        createDirectories(request.outputBinary(), request.logFile());
        removeExistingOutputBinary(request.outputBinary());
        List<String> command = command(request);
        ProcessResult result = processLauncher.run(command, progress);
        writeLog(request.logFile(), result.output());
        if (result.exitCode() != 0) {
            throw new NativeImageException(
                    "native-image failed with exit code "
                            + result.exitCode()
                            + ". Review "
                            + request.logFile()
                            + ", fix the Native Image errors, and try again.\n"
                            + result.output().stripTrailing());
        }
        requireOutputBinary(request.outputBinary());
        return new NativeImageResult(request.outputBinary(), request.logFile(), result.output());
    }

    private List<String> command(NativeImageRequest request) {
        List<String> command = new ArrayList<>();
        command.add(request.executable().toString());
        command.addAll(request.arguments());
        command.add("-cp");
        command.add(joinedClasspath(request));
        command.add(request.mainClass());
        command.add("-o");
        command.add(request.outputBinary().toString());
        return List.copyOf(command);
    }

    private String joinedClasspath(NativeImageRequest request) {
        StringJoiner joiner = new StringJoiner(pathSeparator);
        joiner.add(request.jarPath().normalize().toString());
        for (Path entry : request.runtimeClasspath()) {
            joiner.add(entry.normalize().toString());
        }
        return joiner.toString();
    }

    private static void validate(NativeImageRequest request) {
        if (request.jarPath() == null) {
            throw new NativeImageException("Native Image jar path is missing. Run zolt package before zolt native.");
        }
        if (request.mainClass() == null || request.mainClass().isBlank()) {
            throw new NativeImageException("Native Image main class is missing. Add [project].main to zolt.toml.");
        }
        if (request.outputBinary() == null) {
            throw new NativeImageException("Native Image output path is missing. Check [native].output and [native].imageName.");
        }
        if (request.logFile() == null) {
            throw new NativeImageException("Native Image log path is missing. Check the native output directory.");
        }
        if (request.outputBinary().toAbsolutePath().normalize()
                .equals(request.logFile().toAbsolutePath().normalize())) {
            throw new NativeImageException(
                    "Native Image output binary and log must be distinct paths. "
                            + "Change [native].imageName or [native].output.");
        }
    }

    private static void createDirectories(Path outputBinary, Path logFile) {
        try {
            if (outputBinary.getParent() != null) {
                Files.createDirectories(outputBinary.getParent());
            }
            if (logFile.getParent() != null) {
                Files.createDirectories(logFile.getParent());
            }
        } catch (IOException exception) {
            throw new NativeImageException(
                    "Could not create Native Image output directories. Check that the project directory is writable.",
                    exception);
        }
    }

    private static void removeExistingOutputBinary(Path outputBinary) {
        try {
            Files.deleteIfExists(outputBinary);
        } catch (IOException exception) {
            throw new NativeImageException(
                    "Could not remove existing Native Image output binary at "
                            + outputBinary
                            + ". Check filesystem permissions and retry.",
                    exception);
        }
    }

    private static void requireOutputBinary(Path outputBinary) {
        if (!Files.isRegularFile(outputBinary)) {
            throw new NativeImageException(
                    "Native Image completed but did not create expected binary at "
                            + outputBinary
                            + ". Review the native-image output and retry.");
        }
    }

    private static void writeLog(Path logFile, String output) {
        try {
            Files.writeString(logFile, output, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new NativeImageException(
                    "Could not write Native Image log at "
                            + logFile
                            + ". Check that the output directory is writable.",
                    exception);
        }
    }

    private static ProcessResult runProcess(
            List<String> command,
            Runnable progress,
            Duration heartbeatInterval) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readProcessOutput(process));
            while (!process.waitFor(heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS)) {
                progress.run();
            }
            int exitCode = process.exitValue();
            return resultFrom(exitCode, output);
        } catch (IOException exception) {
            throw new NativeImageException(
                    "Could not run native-image. Install GraalVM Native Image or configure the native-image executable.",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new NativeImageException("native-image was interrupted. Try the native build again.", exception);
        }
    }

    private static String readProcessOutput(Process process) {
        try {
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static ProcessResult resultFrom(int exitCode, CompletableFuture<String> output) {
        try {
            return new ProcessResult(exitCode, output.join());
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof UncheckedIOException ioException) {
                throw new NativeImageException(
                        "Could not read native-image output. Check process output permissions.",
                        ioException.getCause());
            }
            throw exception;
        }
    }

    @FunctionalInterface
    interface ProcessRunner {
        ProcessResult run(List<String> command);
    }

    @FunctionalInterface
    interface ProcessLauncher {
        ProcessResult run(List<String> command, Runnable progress);
    }

    record ProcessResult(int exitCode, String output) {
    }
}
