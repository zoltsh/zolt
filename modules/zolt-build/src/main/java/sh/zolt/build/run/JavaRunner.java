package sh.zolt.build.run;

import sh.zolt.build.JavaRunException;
import sh.zolt.cancel.BuildCancellation;
import sh.zolt.cancel.ProcessCancellation;
import sh.zolt.classpath.Classpath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Consumer;

public final class JavaRunner {
    private final String pathSeparator;
    private final ProcessRunner processRunner;

    public JavaRunner() {
        this(java.io.File.pathSeparator, new ProcessRunner() {
            @Override
            public ProcessResult run(List<String> command, Consumer<String> outputConsumer) {
                return runProcess(command, outputConsumer);
            }

            @Override
            public ProcessResult run(
                    List<String> command,
                    Map<String, String> environment,
                    Consumer<String> outputConsumer) {
                return runProcess(command, environment, outputConsumer);
            }
        });
    }

    public JavaRunner(String pathSeparator, ProcessRunner processRunner) {
        this.pathSeparator = pathSeparator;
        this.processRunner = processRunner;
    }

    public JavaRunResult run(
            Path java,
            Classpath classpath,
            String mainClass,
            List<String> arguments) {
        return run(java, classpath, mainClass, arguments, ignored -> {
        });
    }

    public JavaRunResult run(
            Path java,
            Classpath classpath,
            String mainClass,
            List<String> jvmArguments,
            List<String> arguments) {
        return run(java, classpath, mainClass, jvmArguments, arguments, ignored -> {
        });
    }

    public JavaRunResult run(
            Path java,
            Classpath classpath,
            String mainClass,
            List<String> jvmArguments,
            List<String> arguments,
            Map<String, String> environment) {
        return run(java, classpath, mainClass, jvmArguments, arguments, environment, ignored -> {
        });
    }

    public JavaRunResult run(
            Path java,
            Classpath classpath,
            String mainClass,
            List<String> arguments,
            Consumer<String> outputConsumer) {
        return run(java, classpath, mainClass, List.of(), arguments, outputConsumer);
    }

    public JavaRunResult run(
            Path java,
            Classpath classpath,
            String mainClass,
            List<String> jvmArguments,
            List<String> arguments,
            Consumer<String> outputConsumer) {
        List<String> command = command(java, classpath, mainClass, jvmArguments, arguments);
        ProcessResult result = processRunner.run(command, outputConsumer);
        return result(mainClass, result);
    }

    public JavaRunResult run(
            Path java,
            Classpath classpath,
            String mainClass,
            List<String> jvmArguments,
            List<String> arguments,
            Map<String, String> environment,
            Consumer<String> outputConsumer) {
        List<String> command = command(java, classpath, mainClass, jvmArguments, arguments);
        ProcessResult result = processRunner.run(command, environment, outputConsumer);
        return result(mainClass, result);
    }

    public JavaRunResult runJar(
            Path java,
            Path jar,
            String mainClass,
            List<String> arguments) {
        return runJar(java, jar, mainClass, arguments, ignored -> {
        });
    }

    public JavaRunResult runJar(
            Path java,
            Path jar,
            String mainClass,
            List<String> arguments,
            Consumer<String> outputConsumer) {
        List<String> command = jarCommand(java, jar, arguments);
        ProcessResult result = processRunner.run(command, outputConsumer);
        return result(mainClass, result);
    }

    private static final int SIGNAL_EXIT_BASE = 128;

    private static JavaRunResult result(String mainClass, ProcessResult result) {
        int exitCode = result.exitCode();
        if (exitCode >= SIGNAL_EXIT_BASE) {
            return new JavaRunResult(mainClass, result.output(), exitCode - SIGNAL_EXIT_BASE);
        }
        if (exitCode != 0) {
            throw new JavaRunException(
                    "java exited with code "
                            + exitCode
                            + ". Check the application output and try again.\n"
                            + result.output().stripTrailing());
        }
        return new JavaRunResult(mainClass, result.output());
    }

    private List<String> command(
            Path java,
            Classpath classpath,
            String mainClass,
            List<String> jvmArguments,
            List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.addAll(jvmArguments);
        if (!classpath.entries().isEmpty()) {
            command.add("-classpath");
            StringJoiner joiner = new StringJoiner(pathSeparator);
            for (Path entry : classpath.entries()) {
                joiner.add(entry.normalize().toString());
            }
            command.add(joiner.toString());
        }
        command.add(mainClass);
        command.addAll(arguments);
        return List.copyOf(command);
    }

    private static List<String> jarCommand(
            Path java,
            Path jar,
            List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-jar");
        command.add(jar.normalize().toString());
        command.addAll(arguments);
        return List.copyOf(command);
    }

    private static ProcessResult runProcess(List<String> command, Consumer<String> outputConsumer) {
        return runProcess(command, Map.of(), outputConsumer);
    }

    private static ProcessResult runProcess(
            List<String> command,
            Map<String, String> environment,
            Consumer<String> outputConsumer) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
            if (environment != null && !environment.isEmpty()) {
                processBuilder.environment().putAll(environment);
            }
            Process process = processBuilder.start();
            try (BuildCancellation.Registration ignored =
                    ProcessCancellation.register(process)) {
                StringBuilder output = new StringBuilder();
                byte[] buffer = new byte[8192];
                int read = process.getInputStream().read(buffer);
                while (read >= 0) {
                    String chunk = new String(buffer, 0, read, StandardCharsets.UTF_8);
                    output.append(chunk);
                    outputConsumer.accept(chunk);
                    read = process.getInputStream().read(buffer);
                }
                int exitCode = process.waitFor();
                return new ProcessResult(exitCode, output.toString());
            }
        } catch (IOException exception) {
            throw new JavaRunException(
                    "Could not run java. Check that the configured JDK is installed and readable.",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new JavaRunException("java was interrupted. Try the command again.", exception);
        }
    }

    @FunctionalInterface
    public interface ProcessRunner {
        ProcessResult run(List<String> command, Consumer<String> outputConsumer);

        default ProcessResult run(
                List<String> command,
                Map<String, String> environment,
                Consumer<String> outputConsumer) {
            return run(command, outputConsumer);
        }
    }

    public record ProcessResult(int exitCode, String output) {
    }
}
