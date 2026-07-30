package sh.zolt.quarkus.testworker;

import sh.zolt.cancel.BuildCancellation;
import sh.zolt.cancel.ProcessCancellation;
import sh.zolt.quarkus.QuarkusAugmentationException;
import sh.zolt.quarkus.testworker.descriptor.QuarkusTestRunnerDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

public final class QuarkusTestWorkerLauncher {
    public static final String WORKER_CLASSPATH_PROPERTY = "zolt.quarkus.test-worker.classpath";

    private final String pathSeparator;
    private final Path javaExecutable;
    private final List<Path> workerClasspath;
    private final ProcessRunner processRunner;

    public QuarkusTestWorkerLauncher(
            Path javaExecutable,
            List<Path> workerClasspath) {
        this(java.io.File.pathSeparator, javaExecutable, workerClasspath, QuarkusTestWorkerLauncher::runProcess);
    }

    QuarkusTestWorkerLauncher(
            String pathSeparator,
            Path javaExecutable,
            List<Path> workerClasspath,
            ProcessRunner processRunner) {
        if (pathSeparator == null || pathSeparator.isBlank()) {
            throw new QuarkusAugmentationException("Quarkus test worker path separator is required.");
        }
        if (javaExecutable == null) {
            throw new QuarkusAugmentationException("Quarkus test worker Java executable is required.");
        }
        if (workerClasspath == null || workerClasspath.isEmpty()) {
            throw new QuarkusAugmentationException("Quarkus test worker classpath is required.");
        }
        if (processRunner == null) {
            throw new QuarkusAugmentationException("Quarkus test worker process runner is required.");
        }
        this.pathSeparator = pathSeparator;
        this.javaExecutable = javaExecutable;
        this.workerClasspath = List.copyOf(workerClasspath);
        this.processRunner = processRunner;
    }

    public String run(QuarkusTestRunnerDescriptor descriptor) {
        if (descriptor == null) {
            throw new QuarkusAugmentationException("Quarkus test runner descriptor is required.");
        }
        ProcessResult result = processRunner.run(command(descriptor));
        if (result.exitCode() != 0) {
            throw new QuarkusAugmentationException(
                    "Quarkus test worker failed with exit code "
                            + result.exitCode()
                            + ". Inspect the worker output, fix failing tests or unsupported Quarkus test shapes, "
                            + "then run `zolt test` again.\n"
                            + result.output().stripTrailing());
        }
        return result.output();
    }

    List<String> command(QuarkusTestRunnerDescriptor descriptor) {
        if (descriptor == null) {
            throw new QuarkusAugmentationException("Quarkus test runner descriptor is required.");
        }
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-D" + WORKER_CLASSPATH_PROPERTY + "=" + joinedAbsolute(workerClasspath));
        command.add("-classpath");
        command.add(joinedClasspath(descriptor));
        command.add(QuarkusTestWorker.MAIN_CLASS);
        command.add(descriptor.descriptorFile().toString());
        return List.copyOf(command);
    }

    private String joinedClasspath(QuarkusTestRunnerDescriptor descriptor) {
        StringJoiner joiner = new StringJoiner(pathSeparator);
        joiner.add(joined(workerClasspath));
        for (Path entry : descriptor.testRuntimeClasspath()) {
            joiner.add(entry.normalize().toString());
        }
        return joiner.toString();
    }

    private String joined(List<Path> classpath) {
        StringJoiner joiner = new StringJoiner(pathSeparator);
        for (Path entry : classpath) {
            joiner.add(entry.normalize().toString());
        }
        return joiner.toString();
    }

    private String joinedAbsolute(List<Path> classpath) {
        StringJoiner joiner = new StringJoiner(pathSeparator);
        for (Path entry : classpath) {
            joiner.add(entry.toAbsolutePath().normalize().toString());
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
                    "Could not run Quarkus test worker. Check that the configured JDK is installed and readable.",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QuarkusAugmentationException("Quarkus test worker was interrupted. Try again.", exception);
        }
    }

    @FunctionalInterface
    interface ProcessRunner {
        ProcessResult run(List<String> command);
    }

    record ProcessResult(int exitCode, String output) {
    }
}
