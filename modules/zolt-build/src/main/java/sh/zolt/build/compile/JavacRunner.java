package sh.zolt.build.compile;

import sh.zolt.build.JavacException;
import sh.zolt.cancel.BuildCancellation;
import sh.zolt.cancel.ProcessCancellation;
import sh.zolt.build.incremental.GeneratedOutputAttribution;
import sh.zolt.classpath.Classpath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

public final class JavacRunner {
    private static final Pattern MISSING_PACKAGE_PATTERN =
            Pattern.compile("package \\S+ does not exist");

    private final JavacCommandBuilder commandBuilder;
    private final ProcessRunner processRunner;
    private final InProcessRunner inProcessRunner;
    private final WorkerRunner workerRunner;
    private final Path runtimeJavac;

    public JavacRunner() {
        this(
                java.io.File.pathSeparator,
                JavacRunner::runProcess,
                JavacRunner::runInProcess,
                JavacWorkerPool::compile,
                runtimeJavac());
    }

    JavacRunner(String pathSeparator, ProcessRunner processRunner) {
        this(pathSeparator, processRunner, null, null, null);
    }

    JavacRunner(
            String pathSeparator,
            ProcessRunner processRunner,
            InProcessRunner inProcessRunner,
            Path runtimeJavac) {
        this(pathSeparator, processRunner, inProcessRunner, null, runtimeJavac);
    }

    JavacRunner(
            String pathSeparator,
            ProcessRunner processRunner,
            InProcessRunner inProcessRunner,
            WorkerRunner workerRunner,
            Path runtimeJavac) {
        this.commandBuilder = new JavacCommandBuilder(pathSeparator);
        this.processRunner = processRunner;
        this.inProcessRunner = inProcessRunner;
        this.workerRunner = workerRunner;
        this.runtimeJavac = runtimeJavac;
    }

    public JavacResult compile(
            Path javac,
            List<Path> sources,
            Classpath classpath,
            Path outputDirectory) {
        return compile(javac, sources, classpath, outputDirectory, new Classpath(List.of()), null);
    }

    public JavacResult compile(
            Path javac,
            List<Path> sources,
            Classpath classpath,
            Path outputDirectory,
            Classpath processorClasspath,
            Path generatedSourcesDirectory) {
        return compile(
                javac,
                sources,
                classpath,
                outputDirectory,
                processorClasspath,
                generatedSourcesDirectory,
                JavacOptions.empty());
    }

    public JavacResult compile(
            Path javac,
            List<Path> sources,
            Classpath classpath,
            Path outputDirectory,
            Classpath processorClasspath,
            Path generatedSourcesDirectory,
            JavacOptions options) {
        return compile(
                javac,
                sources,
                classpath,
                outputDirectory,
                processorClasspath,
                generatedSourcesDirectory,
                options,
                false);
    }

    public JavacResult compile(
            Path javac,
            List<Path> sources,
            Classpath classpath,
            Path outputDirectory,
            Classpath processorClasspath,
            Path generatedSourcesDirectory,
            JavacOptions options,
            boolean captureAttribution) {
        JavacOptions effectiveOptions = options == null ? JavacOptions.empty() : options;
        List<Path> sortedSources = sources.stream().map(Path::normalize).sorted().toList();
        Path effectiveGeneratedSourcesDirectory = JavacCommandBuilder.sortedEntries(processorClasspath).isEmpty()
                ? null
                : generatedSourcesDirectory;
        try {
            Files.createDirectories(outputDirectory);
            if (effectiveGeneratedSourcesDirectory != null) {
                Files.createDirectories(effectiveGeneratedSourcesDirectory);
            }
        } catch (IOException exception) {
            throw new JavacException(
                    sh.zolt.error.ActionableError.of(
                            "Could not create compilation output directories for " + outputDirectory + ".",
                            "Check that the project directory is writable.",
                            exception));
        }
        if (sortedSources.isEmpty()) {
            return new JavacResult(0, outputDirectory, "");
        }

        List<String> command = commandBuilder.command(
                javac,
                sortedSources,
                classpath,
                outputDirectory,
                processorClasspath,
                effectiveGeneratedSourcesDirectory,
                effectiveOptions);
        List<String> arguments = command.subList(1, command.size());
        ProcessResult result = dispatch(
                javac, command, arguments, processorClasspath, effectiveOptions, captureAttribution);
        if (result.exitCode() != 0) {
            String diagnostics = result.output().stripTrailing();
            String summary = "javac failed with exit code " + result.exitCode() + "."
                    + (diagnostics.isEmpty() ? "" : "\n" + diagnostics);
            throw new JavacException(sh.zolt.error.ActionableError.of(summary, remediation(diagnostics)));
        }
        return new JavacResult(sortedSources.size(), outputDirectory, result.output(), result.attribution());
    }

    private ProcessResult dispatch(
            Path javac,
            List<String> command,
            List<String> arguments,
            Classpath processorClasspath,
            JavacOptions options,
            boolean captureAttribution) {
        boolean noJvmArgs = options.arguments().stream().noneMatch(argument -> argument.startsWith("-J"));
        if (JavacCommandBuilder.sortedEntries(processorClasspath).isEmpty()) {
            if (canRunInProcess(javac) && noJvmArgs) {
                return inProcessRunner.run(arguments);
            }
            return worker(javac, JavacWorkerWire.KIND_COMPILE, arguments, noJvmArgs)
                    .orElseGet(() -> processRunner.run(command));
        }
        if (captureAttribution && noJvmArgs) {
            return worker(javac, JavacWorkerWire.KIND_COMPILE_ATTRIBUTED, arguments, true)
                    .orElseGet(() -> processRunner.run(command));
        }
        return processRunner.run(command);
    }

    private Optional<ProcessResult> worker(Path javac, int kind, List<String> arguments, boolean allowed) {
        if (workerRunner == null || !allowed) {
            return Optional.empty();
        }
        return workerRunner.run(javac, kind, arguments);
    }

    private boolean canRunInProcess(Path javac) {
        return !BuildCancellation.active()
                && inProcessRunner != null
                && runtimeJavac != null
                && sameExecutable(javac, runtimeJavac);
    }

    private static String remediation(String diagnostics) {
        if (isMissingDependencyFailure(diagnostics)) {
            return "Fix the Java compilation errors and try again. "
                    + "A referenced package could not be found, so a required dependency is likely "
                    + "missing or unresolved: check the declared dependencies and zolt.lock.";
        }
        return "Fix the Java compilation errors and try again. "
                + "If annotation processing is configured, inspect [annotationProcessors], "
                + "[test.annotationProcessors], and processor-scoped entries in zolt.lock.";
    }

    private static boolean isMissingDependencyFailure(String diagnostics) {
        return MISSING_PACKAGE_PATTERN.matcher(diagnostics).find();
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
            throw new JavacException(
                    sh.zolt.error.ActionableError.of(
                            "Could not run javac.",
                            "Check that the configured JDK is installed and readable.",
                            exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new JavacException(
                    sh.zolt.error.ActionableError.of(
                            "javac was interrupted.", "Try the build again.", exception));
        }
    }

    private static ProcessResult runInProcess(List<String> arguments) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return runProcess(withJavacExecutable(runtimeJavac(), arguments));
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int exitCode = compiler.run(
                null,
                output,
                output,
                arguments.toArray(String[]::new));
        return new ProcessResult(exitCode, output.toString(StandardCharsets.UTF_8));
    }

    private static List<String> withJavacExecutable(Path javac, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(javac.toString());
        command.addAll(arguments);
        return List.copyOf(command);
    }

    private static Path runtimeJavac() {
        return runtimeJavac(
                System.getProperty("java.home"),
                System.getProperty("org.graalvm.nativeimage.imagecode"));
    }

    static Path runtimeJavac(String javaHome, String nativeImageCode) {
        if (nativeImageCode != null || javaHome == null || javaHome.isBlank()) {
            return null;
        }
        return Path.of(javaHome)
                .resolve("bin")
                .resolve(executable("javac"));
    }

    private static boolean sameExecutable(Path left, Path right) {
        Path normalizedLeft = left.toAbsolutePath().normalize();
        Path normalizedRight = right.toAbsolutePath().normalize();
        if (Files.exists(normalizedLeft) && Files.exists(normalizedRight)) {
            try {
                return Files.isSameFile(normalizedLeft, normalizedRight);
            } catch (IOException exception) {
                return false;
            }
        }
        return normalizedLeft.equals(normalizedRight);
    }

    private static String executable(String name) {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? name + ".exe"
                : name;
    }

    @FunctionalInterface
    interface ProcessRunner {
        ProcessResult run(List<String> command);
    }

    @FunctionalInterface
    interface InProcessRunner {
        ProcessResult run(List<String> arguments);
    }

    @FunctionalInterface
    interface WorkerRunner {
        Optional<ProcessResult> run(Path javac, int kind, List<String> arguments);
    }

    record ProcessResult(int exitCode, String output, GeneratedOutputAttribution attribution) {
        ProcessResult(int exitCode, String output) {
            this(exitCode, output, GeneratedOutputAttribution.absent());
        }
    }
}
