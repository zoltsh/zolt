package sh.zolt.junit;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

public final class JunitWorkerProcessLauncher {
    public static final String DEFAULT_MAIN_CLASS = "sh.zolt.junit.JunitLauncherWorker";

    private static final int CLOSE_TIMEOUT_SECONDS = 5;

    private final String pathSeparator;
    private final Path javaExecutable;
    private final List<Path> workerClasspath;
    private final ProcessStarter processStarter;

    public JunitWorkerProcessLauncher(
            Path javaExecutable,
            List<Path> workerClasspath) {
        this(java.io.File.pathSeparator, javaExecutable, workerClasspath, new ProcessStarter() {
            @Override
            public StartedWorker start(List<String> command, Path projectDirectory) {
                return startProcess(command, projectDirectory);
            }

            @Override
            public StartedWorker start(
                    List<String> command,
                    Path projectDirectory,
                    Map<String, String> environment) {
                return startProcess(command, projectDirectory, environment);
            }
        });
    }

    JunitWorkerProcessLauncher(
            String pathSeparator,
            Path javaExecutable,
            List<Path> workerClasspath,
            ProcessStarter processStarter) {
        if (pathSeparator == null || pathSeparator.isBlank()) {
            throw new JunitWorkerClientException("JUnit worker path separator is required.");
        }
        if (javaExecutable == null) {
            throw new JunitWorkerClientException("JUnit worker Java executable is required.");
        }
        if (workerClasspath == null || workerClasspath.isEmpty()) {
            throw new JunitWorkerClientException("JUnit worker classpath is required.");
        }
        if (processStarter == null) {
            throw new JunitWorkerClientException("JUnit worker process starter is required.");
        }
        this.pathSeparator = pathSeparator;
        this.javaExecutable = javaExecutable;
        this.workerClasspath = List.copyOf(workerClasspath);
        this.processStarter = processStarter;
    }

    public JunitWorkerProcess start(
            Path projectDirectory,
            List<Path> testRuntimeClasspath) {
        return start(projectDirectory, testRuntimeClasspath, List.of());
    }

    public JunitWorkerProcess start(
            Path projectDirectory,
            List<Path> testRuntimeClasspath,
            List<String> jvmArguments) {
        return start(projectDirectory, testRuntimeClasspath, jvmArguments, Map.of());
    }

    public JunitWorkerProcess start(
            Path projectDirectory,
            List<Path> testRuntimeClasspath,
            List<String> jvmArguments,
            Map<String, String> environment) {
        if (projectDirectory == null) {
            throw new JunitWorkerClientException("JUnit worker project directory is required.");
        }
        if (testRuntimeClasspath == null || testRuntimeClasspath.isEmpty()) {
            throw new JunitWorkerClientException("JUnit worker test runtime classpath is required.");
        }
        StartedWorker worker = processStarter.start(
                command(projectDirectory, testRuntimeClasspath, jvmArguments),
                projectDirectory,
                environment == null ? Map.of() : environment);
        return new JunitWorkerProcess(
                new JunitWorkerClient(worker.output(), worker.input()),
                () -> worker.processCloser().close(),
                testRuntimeClasspath.stream()
                        .map(path -> path.toAbsolutePath().normalize())
                        .toList());
    }

    List<String> command(
            Path projectDirectory,
            List<Path> testRuntimeClasspath) {
        return command(projectDirectory, testRuntimeClasspath, List.of());
    }

    List<String> command(
            Path projectDirectory,
            List<Path> testRuntimeClasspath,
            List<String> jvmArguments) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.addAll(jvmArguments == null ? List.of() : jvmArguments);
        command.add("-Duser.dir=" + projectDirectory.toAbsolutePath().normalize());
        command.add("-classpath");
        command.add(joinedClasspath(testRuntimeClasspath));
        command.add(DEFAULT_MAIN_CLASS);
        command.add("--server");
        return List.copyOf(command);
    }

    private String joinedClasspath(List<Path> testRuntimeClasspath) {
        StringJoiner joiner = new StringJoiner(pathSeparator);
        for (Path entry : workerClasspath) {
            joiner.add(entry.toAbsolutePath().normalize().toString());
        }
        for (Path entry : testRuntimeClasspath) {
            joiner.add(entry.toAbsolutePath().normalize().toString());
        }
        return joiner.toString();
    }

    private static StartedWorker startProcess(List<String> command, Path projectDirectory) {
        return startProcess(command, projectDirectory, Map.of());
    }

    private static StartedWorker startProcess(
            List<String> command,
            Path projectDirectory,
            Map<String, String> environment) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .directory(projectDirectory.toFile())
                    .redirectErrorStream(true);
            if (environment != null && !environment.isEmpty()) {
                processBuilder.environment().putAll(environment);
            }
            Process process = processBuilder.start();
            return new StartedWorker(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8),
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8),
                    new JunitWorkerProcess.ProcessCloser() {
                        @Override
                        public void close() {
                            closeProcess(process);
                        }

                        @Override
                        public void abort() {
                            abortProcess(process);
                        }
                    });
        } catch (IOException exception) {
            throw new JunitWorkerClientException(
                    "Could not start JUnit worker. Check that the configured JDK is installed and readable.",
                    exception);
        }
    }

    private static void closeProcess(Process process) {
        try {
            if (!process.waitFor(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new JunitWorkerClientException("JUnit worker shutdown was interrupted. Try again.", exception);
        }
    }

    private static void abortProcess(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(
                    CLOSE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    @FunctionalInterface
    interface ProcessStarter {
        StartedWorker start(List<String> command, Path projectDirectory);

        default StartedWorker start(
                List<String> command,
                Path projectDirectory,
                Map<String, String> environment) {
            return start(command, projectDirectory);
        }
    }

    record StartedWorker(
            java.io.Reader output,
            java.io.Writer input,
            JunitWorkerProcess.ProcessCloser processCloser) {
    }
}
