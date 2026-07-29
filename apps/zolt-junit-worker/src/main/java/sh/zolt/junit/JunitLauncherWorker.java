package sh.zolt.junit;

import sh.zolt.error.WorkerFailureDiagnostic;
import sh.zolt.test.TestSelection;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class JunitLauncherWorker {
    public static final String MAIN_CLASS = "sh.zolt.junit.JunitLauncherWorker";

    public static void main(String[] args) {
        int exitCode = new JunitLauncherWorker().run(args, System.in, System.out, System.err);
        System.exit(exitCode);
    }

    int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, InputStream.nullInputStream(), out, err);
    }

    int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        if (args != null && args.length == 1 && "--server".equals(args[0])) {
            return runServer(in, out, err);
        }
        if (args == null || args.length != 1 || args[0].isBlank()) {
            err.println("error: JUnit launcher worker requires exactly one test output directory.");
            return 2;
        }
        return runOnce(args[0], out, err);
    }

    private int runServer(InputStream in, PrintStream out, PrintStream err) {
        if (in == null) {
            err.println("error: JUnit launcher worker server requires stdin.");
            return 2;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JunitWorkerProtocol.WorkerRequest request = JunitWorkerProtocol.parseRequest(line);
                if (request.command().equals(JunitWorkerProtocol.WorkerCommand.QUIT)) {
                    out.println(JunitWorkerProtocol.result(request.requestId(), 0));
                    out.flush();
                    return 0;
                }
                Set<Long> threadsBefore = liveNonDaemonThreadIds();
                int exitCode = runRequest(
                        request.projectDirectory(),
                        request.testRuntimeClasspath(),
                        request.testOutputDirectory(),
                        request.testSelection(),
                        request.reportsDirectory(),
                        request.profileDirectory(),
                        request.events(),
                        out,
                        err);
                boolean retireWorker =
                        hasNewLiveNonDaemonThreads(threadsBefore);
                out.println(JunitWorkerProtocol.result(
                        request.requestId(),
                        exitCode,
                        retireWorker));
                out.flush();
                if (retireWorker) {
                    return exitCode;
                }
            }
            return 0;
        } catch (IOException exception) {
            err.println("error: Could not read JUnit launcher worker server input.");
            err.println(WorkerFailureDiagnostic.causeLine(exception));
            return 1;
        } catch (IllegalArgumentException exception) {
            err.println("error: " + exception.getMessage());
            return 2;
        }
    }

    private int runOnce(String testOutputDirectory, PrintStream out, PrintStream err) {
        return runRequest(
                Optional.empty(),
                List.of(),
                testOutputDirectory,
                TestSelection.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                out,
                err);
    }

    private int runRequest(
            Optional<String> projectDirectory,
            List<String> testRuntimeClasspath,
            String testOutputDirectory,
            TestSelection testSelection,
            Optional<String> reportsDirectory,
            Optional<String> profileDirectory,
            List<String> events,
            PrintStream out,
            PrintStream err) {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        String originalUserDirectory = System.getProperty("user.dir");
        Path requestDirectory = requestDirectory(projectDirectory);
        try (URLClassLoader requestLoader = requestClassLoader(
                requestDirectory,
                testRuntimeClasspath,
                original)) {
            System.setProperty(
                    "user.dir",
                    requestDirectory.toString());
            Thread.currentThread().setContextClassLoader(requestLoader);
            JunitProgrammaticLauncher launcher =
                    new JunitProgrammaticLauncher(out, requestLoader);
            return execute(
                    launcher,
                    resolveRequestPath(
                            requestDirectory,
                            testOutputDirectory),
                    testSelection,
                    resolveRequestPath(
                            requestDirectory,
                            reportsDirectory),
                    resolveRequestPath(
                            requestDirectory,
                            profileDirectory),
                    events,
                    err);
        } catch (IOException exception) {
            err.println("error: Could not close JUnit request classloader.");
            err.println(WorkerFailureDiagnostic.causeLine(exception));
            return 1;
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            if (originalUserDirectory == null) {
                System.clearProperty("user.dir");
            } else {
                System.setProperty("user.dir", originalUserDirectory);
            }
        }
    }

    private int execute(
            JunitProgrammaticLauncher launcher,
            Path testOutputDirectory,
            TestSelection testSelection,
            Optional<Path> reportsDirectory,
            Optional<Path> profileDirectory,
            List<String> events,
            PrintStream err) {
        try {
            return launcher.execute(
                    testOutputDirectory,
                    testSelection,
                    reportsDirectory,
                    profileDirectory,
                    events);
        } catch (ReflectiveOperationException | IOException | LinkageError exception) {
            err.println("error: Could not run tests through Zolt's JUnit launcher worker. "
                    + "Check that JUnit Platform Launcher and test engines are on the worker classpath.");
            err.println(WorkerFailureDiagnostic.causeLine(
                    diagnosticCause(exception)));
            return 1;
        }
    }

    private static Throwable diagnosticCause(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current.getCause() != null
                && current.getCause() != current
                && depth < 16) {
            current = current.getCause();
            depth++;
        }
        return current;
    }

    private static URLClassLoader requestClassLoader(
            Path requestDirectory,
            List<String> classpath,
            ClassLoader fallback) {
        if (classpath == null || classpath.isEmpty()) {
            return new URLClassLoader(new URL[0], fallback);
        }
        URL[] urls = classpath.stream()
                .map(path -> resolveRequestPath(
                        requestDirectory,
                        path))
                .map(JunitLauncherWorker::url)
                .toArray(URL[]::new);
        return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
    }

    private static Path requestDirectory(
            Optional<String> projectDirectory) {
        return projectDirectory
                .filter(directory -> !directory.isBlank())
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .orElseGet(() -> Path.of("")
                        .toAbsolutePath()
                        .normalize());
    }

    private static Path resolveRequestPath(
            Path requestDirectory,
            String value) {
        Path path = Path.of(value);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return requestDirectory.resolve(path).normalize();
    }

    private static Optional<Path> resolveRequestPath(
            Path requestDirectory,
            Optional<String> value) {
        return value.map(path ->
                resolveRequestPath(requestDirectory, path));
    }

    private static URL url(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException exception) {
            throw new IllegalArgumentException(
                    "Invalid JUnit worker classpath entry `" + path + "`.",
                    exception);
        }
    }

    static Set<Long> liveNonDaemonThreadIds() {
        Thread current = Thread.currentThread();
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> !thread.isDaemon())
                .filter(thread -> thread != current)
                .map(Thread::threadId)
                .collect(Collectors.toUnmodifiableSet());
    }

    static boolean hasNewLiveNonDaemonThreads(Set<Long> threadsBefore) {
        Set<Long> baseline = threadsBefore == null
                ? Set.of()
                : Set.copyOf(threadsBefore);
        return liveNonDaemonThreadIds().stream()
                .anyMatch(threadId -> !baseline.contains(threadId));
    }
}
