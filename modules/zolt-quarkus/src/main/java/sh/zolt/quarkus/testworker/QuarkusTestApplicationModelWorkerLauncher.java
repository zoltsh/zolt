package sh.zolt.quarkus.testworker;

import sh.zolt.cancel.BuildCancellation;
import sh.zolt.cancel.ProcessCancellation;
import sh.zolt.quarkus.QuarkusAugmentationException;
import sh.zolt.quarkus.bootstrap.descriptor.QuarkusBootstrapDescriptor;
import sh.zolt.quarkus.bootstrap.QuarkusWorkspaceModuleInputs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

public final class QuarkusTestApplicationModelWorkerLauncher {
    private final String pathSeparator;
    private final Path javaExecutable;
    private final List<Path> workerClasspath;
    private final ProcessRunner processRunner;

    public QuarkusTestApplicationModelWorkerLauncher(
            Path javaExecutable,
            List<Path> workerClasspath) {
        this(java.io.File.pathSeparator, javaExecutable, workerClasspath, QuarkusTestApplicationModelWorkerLauncher::runProcess);
    }

    QuarkusTestApplicationModelWorkerLauncher(
            String pathSeparator,
            Path javaExecutable,
            List<Path> workerClasspath,
            ProcessRunner processRunner) {
        if (pathSeparator == null || pathSeparator.isBlank()) {
            throw new QuarkusAugmentationException("Quarkus test application model worker path separator is required.");
        }
        if (javaExecutable == null) {
            throw new QuarkusAugmentationException("Quarkus test application model worker Java executable is required.");
        }
        if (workerClasspath == null || workerClasspath.isEmpty()) {
            throw new QuarkusAugmentationException("Quarkus test application model worker classpath is required.");
        }
        if (processRunner == null) {
            throw new QuarkusAugmentationException("Quarkus test application model worker process runner is required.");
        }
        this.pathSeparator = pathSeparator;
        this.javaExecutable = javaExecutable;
        this.workerClasspath = List.copyOf(workerClasspath);
        this.processRunner = processRunner;
    }

    public Path write(
            QuarkusBootstrapDescriptor descriptor,
            Path outputPath,
            QuarkusWorkspaceModuleInputs workspaceModuleInputs) {
        if (descriptor == null) {
            throw new QuarkusAugmentationException("Quarkus bootstrap descriptor is required.");
        }
        if (outputPath == null) {
            throw new QuarkusAugmentationException("Quarkus serialized test application model output path is required.");
        }
        if (workspaceModuleInputs == null) {
            throw new QuarkusAugmentationException("Quarkus workspace module inputs are required.");
        }
        try {
            Files.createDirectories(outputPath.toAbsolutePath().normalize().getParent());
        } catch (IOException exception) {
            throw new QuarkusAugmentationException(
                    "Could not create Quarkus test application model output directory. "
                            + "Check that "
                            + outputPath.toAbsolutePath().normalize().getParent()
                            + " is writable and try again.",
                    exception);
        }
        ProcessResult result = processRunner.run(command(descriptor, outputPath, workspaceModuleInputs));
        if (result.exitCode() != 0) {
            throw new QuarkusAugmentationException(
                    "Quarkus test application model worker failed with exit code "
                            + result.exitCode()
                            + ". Fix the Quarkus test bootstrap inputs and try again.\n"
                            + result.output().stripTrailing());
        }
        Path normalizedOutput = outputPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedOutput)) {
            throw new QuarkusAugmentationException(
                    "Quarkus test application model worker completed without writing "
                            + normalizedOutput
                            + ". Rerun zolt test after cleaning "
                            + normalizedOutput.getParent()
                            + ".");
        }
        return normalizedOutput;
    }

    List<String> command(
            QuarkusBootstrapDescriptor descriptor,
            Path outputPath,
            QuarkusWorkspaceModuleInputs workspaceModuleInputs) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-classpath");
        command.add(joinedClasspath(descriptor));
        command.add(QuarkusTestApplicationModelWorker.MAIN_CLASS);
        command.add(descriptor.descriptorFile().toString());
        command.add(outputPath.toAbsolutePath().normalize().toString());
        command.add(workspaceModuleInputs.projectDirectory().toAbsolutePath().normalize().toString());
        command.add(workspaceModuleInputs.buildDirectory().toAbsolutePath().normalize().toString());
        command.add(workspaceModuleInputs.mainSourceDirectory().toAbsolutePath().normalize().toString());
        command.add(workspaceModuleInputs.mainResourceDirectory().toAbsolutePath().normalize().toString());
        command.add(workspaceModuleInputs.mainOutputDirectory().toAbsolutePath().normalize().toString());
        command.add(workspaceModuleInputs.testSourceDirectory().toAbsolutePath().normalize().toString());
        command.add(workspaceModuleInputs.testResourceDirectory().toAbsolutePath().normalize().toString());
        command.add(workspaceModuleInputs.testOutputDirectory().toAbsolutePath().normalize().toString());
        return List.copyOf(command);
    }

    private String joinedClasspath(QuarkusBootstrapDescriptor descriptor) {
        StringJoiner joiner = new StringJoiner(pathSeparator);
        for (Path entry : workerClasspath) {
            joiner.add(entry.toAbsolutePath().normalize().toString());
        }
        for (Path entry : descriptor.deploymentClasspath()) {
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
                    "Could not run Quarkus test application model worker. Check that the configured JDK is installed and readable.",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QuarkusAugmentationException(
                    "Quarkus test application model worker was interrupted. Try again.",
                    exception);
        }
    }

    @FunctionalInterface
    interface ProcessRunner {
        ProcessResult run(List<String> command);
    }

    record ProcessResult(int exitCode, String output) {
    }
}
