package sh.zolt.quarkus.bootstrap;

import sh.zolt.cancel.BuildCancellation;
import sh.zolt.cancel.ProcessCancellation;
import sh.zolt.quarkus.QuarkusAugmentationException;
import sh.zolt.quarkus.bootstrap.descriptor.QuarkusBootstrapDescriptor;
import sh.zolt.quarkus.production.QuarkusAugmentationRequest;
import sh.zolt.quarkus.production.QuarkusAugmentor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

public final class QuarkusBootstrapWorkerLauncher implements QuarkusAugmentor {
    private final String pathSeparator;
    private final List<Path> workerClasspath;
    private final Path javaExecutable;
    private final ProcessRunner processRunner;
    private final QuarkusBootstrapWorkerResultCodec resultCodec;

    public QuarkusBootstrapWorkerLauncher(
            Path javaExecutable,
            List<Path> workerClasspath) {
        this(java.io.File.pathSeparator, javaExecutable, workerClasspath, QuarkusBootstrapWorkerLauncher::runProcess);
    }

    QuarkusBootstrapWorkerLauncher(
            String pathSeparator,
            Path javaExecutable,
            List<Path> workerClasspath,
            ProcessRunner processRunner) {
        this(pathSeparator, javaExecutable, workerClasspath, processRunner, new QuarkusBootstrapWorkerResultCodec());
    }

    QuarkusBootstrapWorkerLauncher(
            String pathSeparator,
            Path javaExecutable,
            List<Path> workerClasspath,
            ProcessRunner processRunner,
            QuarkusBootstrapWorkerResultCodec resultCodec) {
        if (pathSeparator == null || pathSeparator.isBlank()) {
            throw new QuarkusAugmentationException("Quarkus bootstrap worker path separator is required.");
        }
        if (javaExecutable == null) {
            throw new QuarkusAugmentationException("Quarkus bootstrap worker Java executable is required.");
        }
        if (workerClasspath == null || workerClasspath.isEmpty()) {
            throw new QuarkusAugmentationException("Quarkus bootstrap worker classpath is required.");
        }
        if (processRunner == null) {
            throw new QuarkusAugmentationException("Quarkus bootstrap worker process runner is required.");
        }
        if (resultCodec == null) {
            throw new QuarkusAugmentationException("Quarkus bootstrap worker result codec is required.");
        }
        this.pathSeparator = pathSeparator;
        this.javaExecutable = javaExecutable;
        this.workerClasspath = List.copyOf(workerClasspath);
        this.processRunner = processRunner;
        this.resultCodec = resultCodec;
    }

    @Override
    public QuarkusBootstrapWorkerResult augment(QuarkusAugmentationRequest request, QuarkusBootstrapDescriptor descriptor) {
        if (descriptor == null) {
            throw new QuarkusAugmentationException("Quarkus bootstrap descriptor is required.");
        }
        ProcessResult result = processRunner.run(command(descriptor));
        if (result.exitCode() != 0) {
            throw new QuarkusAugmentationException(
                    "Quarkus bootstrap worker failed with exit code "
                            + result.exitCode()
                            + ". Fix the Quarkus augmentation inputs and try again.\n"
                            + result.output().stripTrailing());
        }
        QuarkusBootstrapWorkerResult workerResult = resultCodec.parse(result.output())
                .orElseThrow(() -> new QuarkusAugmentationException(
                        "Quarkus bootstrap worker completed without a Zolt success marker. "
                                + "Update Zolt or rerun with a clean Quarkus output directory."));
        validateWorkerResult(descriptor, workerResult);
        return workerResult;
    }

    private static void validateWorkerResult(
            QuarkusBootstrapDescriptor descriptor,
            QuarkusBootstrapWorkerResult workerResult) {
        if (!descriptor.inputFingerprint().equals(workerResult.inputFingerprint())) {
            throw new QuarkusAugmentationException(
                    "Quarkus bootstrap worker result fingerprint "
                            + workerResult.inputFingerprint()
                            + " did not match expected fingerprint "
                            + descriptor.inputFingerprint()
                            + ". Rerun zolt build after refreshing Quarkus augmentation inputs.");
        }
        Path expectedPackageDirectory = descriptor.packageDirectory().toAbsolutePath().normalize();
        Path actualPackageDirectory = workerResult.packageDirectory().toAbsolutePath().normalize();
        if (!expectedPackageDirectory.equals(actualPackageDirectory)) {
            throw new QuarkusAugmentationException(
                    "Quarkus bootstrap worker result package directory "
                            + actualPackageDirectory
                            + " did not match expected package directory "
                            + expectedPackageDirectory
                            + ". Check the Quarkus package output layout.");
        }
    }

    List<String> command(QuarkusBootstrapDescriptor descriptor) {
        if (descriptor == null) {
            throw new QuarkusAugmentationException("Quarkus bootstrap descriptor is required.");
        }
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-classpath");
        command.add(joinedClasspath(descriptor));
        command.add(QuarkusBootstrapWorker.MAIN_CLASS);
        command.add(descriptor.descriptorFile().toString());
        return List.copyOf(command);
    }

    private String joinedClasspath(QuarkusBootstrapDescriptor descriptor) {
        StringJoiner joiner = new StringJoiner(pathSeparator);
        for (Path entry : workerClasspath) {
            joiner.add(entry.normalize().toString());
        }
        for (Path entry : descriptor.deploymentClasspath()) {
            joiner.add(entry.normalize().toString());
        }
        return joiner.toString();
    }

    private static ProcessResult runProcess(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            try (BuildCancellation.Registration ignored =
                    ProcessCancellation.register(process)) {
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exitCode = process.waitFor();
                return new ProcessResult(exitCode, output);
            }
        } catch (IOException exception) {
            throw new QuarkusAugmentationException(
                    "Could not run Quarkus bootstrap worker. Check that the configured JDK is installed and readable.",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QuarkusAugmentationException("Quarkus bootstrap worker was interrupted. Try again.", exception);
        }
    }

    @FunctionalInterface
    interface ProcessRunner {
        ProcessResult run(List<String> command);
    }

    record ProcessResult(int exitCode, String output) {
    }
}
