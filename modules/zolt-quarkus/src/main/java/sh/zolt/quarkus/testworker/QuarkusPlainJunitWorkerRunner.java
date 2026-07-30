package sh.zolt.quarkus.testworker;

import sh.zolt.cancel.BuildCancellation;
import sh.zolt.cancel.ProcessCancellation;
import sh.zolt.quarkus.QuarkusAugmentationException;
import sh.zolt.quarkus.testworker.descriptor.QuarkusTestRunnerDescriptor;
import sh.zolt.test.TestSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public final class QuarkusPlainJunitWorkerRunner {
    private static final String CONSOLE_MAIN_CLASS = "org.junit.platform.console.ConsoleLauncher";
    private static final String JBOSS_LOG_MANAGER_PROPERTY =
            "-Djava.util.logging.manager=org.jboss.logmanager.LogManager";

    private final String pathSeparator;
    private final Path javaExecutable;
    private final ProcessRunner processRunner;

    public QuarkusPlainJunitWorkerRunner() {
        this(java.io.File.pathSeparator, defaultJavaExecutable(), new ProcessRunner() {
            @Override
            public Result run(List<String> command) {
                return runProcess(command);
            }

            @Override
            public Result run(List<String> command, Map<String, String> environment) {
                return runProcess(command, environment);
            }
        });
    }

    QuarkusPlainJunitWorkerRunner(
            String pathSeparator,
            Path javaExecutable,
            ProcessRunner processRunner) {
        if (pathSeparator == null || pathSeparator.isBlank()) {
            throw new QuarkusAugmentationException("Quarkus plain JUnit worker path separator is required.");
        }
        if (javaExecutable == null) {
            throw new QuarkusAugmentationException("Quarkus plain JUnit worker Java executable is required.");
        }
        if (processRunner == null) {
            throw new QuarkusAugmentationException("Quarkus plain JUnit worker process runner is required.");
        }
        this.pathSeparator = pathSeparator;
        this.javaExecutable = javaExecutable;
        this.processRunner = processRunner;
    }

    public Result run(QuarkusTestRunnerDescriptor descriptor) {
        if (descriptor == null) {
            throw new QuarkusAugmentationException("Quarkus test runner descriptor is required.");
        }
        return processRunner.run(command(descriptor), descriptor.environment());
    }

    List<String> command(QuarkusTestRunnerDescriptor descriptor) {
        if (descriptor == null) {
            throw new QuarkusAugmentationException("Quarkus test runner descriptor is required.");
        }
        String classpath = joinedClasspath(descriptor.testRuntimeClasspath());
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.addAll(descriptor.jvmArguments().values());
        command.add("-Duser.dir=" + descriptor.projectDirectory());
        command.add("-D"
                + QuarkusTestApplicationModelService.SERIALIZED_TEST_MODEL_PROPERTY
                + "="
                + descriptor.serializedApplicationModel());
        if (descriptor.jbossLogManagerPresent()) {
            command.add(JBOSS_LOG_MANAGER_PROPERTY);
        }
        command.add("-classpath");
        command.add(classpath);
        command.add(CONSOLE_MAIN_CLASS);
        command.add("execute");
        command.add("--disable-banner");
        command.add("--class-path");
        command.add(classpath);
        addSelectionArguments(command, descriptor.testOutputDirectory(), descriptor.testSelection());
        command.add("--details");
        command.add("summary");
        return List.copyOf(command);
    }

    private static void addSelectionArguments(
            List<String> command,
            Path testOutputDirectory,
            TestSelection selection) {
        TestSelection testSelection = selection == null ? TestSelection.empty() : selection;
        boolean hasClassOrMethodSelectors =
                !testSelection.classSelectors().isEmpty() || !testSelection.methodSelectors().isEmpty();
        if (!hasClassOrMethodSelectors) {
            command.add("--scan-class-path=" + testOutputDirectory.normalize());
        }
        for (String classSelector : testSelection.classSelectors()) {
            command.add("--select-class");
            command.add(classSelector);
        }
        for (TestSelection.MethodSelector methodSelector : testSelection.methodSelectors()) {
            command.add("--select-method");
            command.add(methodSelector.className() + "#" + methodSelector.methodName());
        }
        List<String> classNamePatterns = testSelection.classNamePatterns().isEmpty() && !hasClassOrMethodSelectors
                ? TestSelection.defaultScanClassNamePatterns()
                : testSelection.classNameRegexPatterns();
        for (String pattern : classNamePatterns) {
            command.add("--include-classname");
            command.add(pattern);
        }
        for (String tag : testSelection.includedTags()) {
            command.add("--include-tag");
            command.add(tag);
        }
        for (String tag : testSelection.excludedTags()) {
            command.add("--exclude-tag");
            command.add(tag);
        }
    }

    private String joinedClasspath(List<Path> classpath) {
        StringJoiner joiner = new StringJoiner(pathSeparator);
        for (Path entry : classpath) {
            joiner.add(entry.normalize().toString());
        }
        return joiner.toString();
    }

    private static Path defaultJavaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", executableName());
    }

    private static String executableName() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
    }

    private static Result runProcess(List<String> command) {
        return runProcess(command, Map.of());
    }

    private static Result runProcess(List<String> command, Map<String, String> environment) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
            if (environment != null && !environment.isEmpty()) {
                processBuilder.environment().putAll(environment);
            }
            Process process = processBuilder.start();
            try (BuildCancellation.Registration ignored =
                    ProcessCancellation.register(process)) {
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exitCode = process.waitFor();
                return new Result(exitCode, output);
            }
        } catch (IOException exception) {
            throw new QuarkusAugmentationException(
                    "Could not run Quarkus plain JUnit worker. Check that the configured JDK is installed and readable.",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QuarkusAugmentationException("Quarkus plain JUnit worker was interrupted. Try again.", exception);
        }
    }

    @FunctionalInterface
    interface ProcessRunner {
        Result run(List<String> command);

        default Result run(List<String> command, Map<String, String> environment) {
            return run(command);
        }
    }

    public record Result(int exitCode, String output) {
    }
}
