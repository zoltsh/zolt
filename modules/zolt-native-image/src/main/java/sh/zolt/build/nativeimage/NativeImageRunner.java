package sh.zolt.build.nativeimage;

import sh.zolt.build.NativeImageException;
import sh.zolt.process.ProcessInputPolicy;
import sh.zolt.process.ProcessSupervisor;
import sh.zolt.process.SupervisedProcessResult;
import sh.zolt.process.SupervisedProcessSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.Consumer;

public final class NativeImageRunner {
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(60);

    private final String pathSeparator;
    private final ProcessLauncher processLauncher;

    public NativeImageRunner() {
        this(
                java.io.File.pathSeparator,
                (command, progress, output) -> runProcess(command, progress, output, HEARTBEAT_INTERVAL));
    }

    NativeImageRunner(String pathSeparator, ProcessRunner processRunner) {
        this(pathSeparator, (command, progress, output) -> {
            ProcessResult result = processRunner.run(command);
            output.accept(result.output());
            return result;
        });
    }

    NativeImageRunner(String pathSeparator, ProcessLauncher processLauncher) {
        this.pathSeparator = pathSeparator;
        this.processLauncher = processLauncher;
    }

    public NativeImageResult build(NativeImageRequest request) {
        return build(request, () -> {});
    }

    public NativeImageResult build(NativeImageRequest request, Runnable progress) {
        return build(request, progress, ignored -> {});
    }

    NativeImageResult build(
            NativeImageRequest request,
            Runnable progress,
            Consumer<NativeImageResult> acceptance) {
        validate(request);
        createDirectories(request.outputBinary(), request.logFile());
        Path stagingBinary = NativeBinaryPublication.stagingPath(request.outputBinary());
        try {
            ProcessResult result;
            try (NativeImageLog log = NativeImageLog.open(request.logFile())) {
                result = processLauncher.run(
                        command(request, stagingBinary),
                        progress,
                        log::append);
                requireSuccessfulExit(request, result);
                NativeBinaryPublication.requireCandidate(stagingBinary, request.outputBinary());
            }
            NativeImageResult nativeResult = new NativeImageResult(
                    request.outputBinary(),
                    request.logFile(),
                    result.output());
            acceptance.accept(nativeResult);
            NativeBinaryPublication.publish(stagingBinary, request.outputBinary());
            return nativeResult;
        } catch (RuntimeException | Error failure) {
            removeFailedStaging(stagingBinary, failure);
            throw failure;
        }
    }

    private static void removeFailedStaging(Path stagingBinary, Throwable failure) {
        try {
            NativeBinaryPublication.removeStaging(stagingBinary);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private List<String> command(NativeImageRequest request, Path stagingBinary) {
        List<String> command = new ArrayList<>();
        command.add(request.executable().toString());
        command.addAll(request.arguments());
        command.add("-cp");
        command.add(joinedClasspath(request));
        command.add(request.mainClass());
        command.add("-o");
        command.add(stagingBinary.toString());
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
        if (NativePathOwnership.overlaps(request.outputBinary(), request.logFile())) {
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

    private static void requireSuccessfulExit(NativeImageRequest request, ProcessResult result) {
        if (result.exitCode() == 0) {
            return;
        }
        throw new NativeImageException(
                "native-image failed with exit code "
                        + result.exitCode()
                        + ". Review "
                        + request.logFile()
                        + ", fix the Native Image errors, and try again.\n"
                        + result.output().stripTrailing());
    }

    private static ProcessResult runProcess(
            List<String> command,
            Runnable progress,
            Consumer<String> output,
            Duration heartbeatInterval) {
        try {
            SupervisedProcessResult result = new ProcessSupervisor().run(
                    SupervisedProcessSpec.builder(command)
                            .inputPolicy(ProcessInputPolicy.CLOSED)
                            .stdoutConsumer(output)
                            .build(),
                    heartbeatInterval,
                    progress);
            if (result.terminationInitiatedByZolt()) {
                throw new NativeImageException(
                        "native-image was cancelled. The previous native binary was preserved; try the native build again.");
            }
            return new ProcessResult(result.exitCode(), result.diagnosticTail());
        } catch (IOException exception) {
            throw new NativeImageException(
                    "Could not run native-image. Install GraalVM Native Image or configure the native-image executable.",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new NativeImageException(
                    "native-image was interrupted. The previous native binary was preserved; try the native build again.",
                    exception);
        }
    }

    @FunctionalInterface
    interface ProcessRunner {
        ProcessResult run(List<String> command);
    }

    @FunctionalInterface
    interface ProcessLauncher {
        ProcessResult run(List<String> command, Runnable progress, Consumer<String> output);
    }

    record ProcessResult(int exitCode, String output) {
    }
}
