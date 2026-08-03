package sh.zolt.build.testruntime.execution;

import sh.zolt.test.runtime.TestRunException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public final class CurrentWorkerClasspath {
    static final String PROPERTY = "zolt.junit.worker.classpath";
    static final String ENVIRONMENT = "ZOLT_JUNIT_WORKER_CLASSPATH";
    private static final String BUNDLED_WORKER_JAR = "zolt-junit-worker.jar";

    public List<Path> discover() {
        return discover(
                System.getProperty(PROPERTY, ""),
                System.getenv(ENVIRONMENT),
                this::bundledWorkerClasspath,
                System.getProperty("java.class.path", ""),
                java.io.File.pathSeparator);
    }

    public boolean dedicatedWorkerAvailable() {
        return dedicatedWorkerAvailable(
                System.getProperty(PROPERTY, ""),
                System.getenv(ENVIRONMENT),
                this::bundledWorkerClasspath,
                java.io.File.pathSeparator);
    }

    boolean dedicatedWorkerAvailable(
            String configuredProperty,
            String configuredEnvironment,
            Supplier<List<Path>> bundledWorkerClasspath,
            String pathSeparator) {
        return !classpathEntries(configuredProperty, pathSeparator).isEmpty()
                || !classpathEntries(configuredEnvironment, pathSeparator).isEmpty()
                || (bundledWorkerClasspath != null
                        && !bundledWorkerClasspath.get().isEmpty());
    }

    List<Path> discover(String classpath, String pathSeparator) {
        return discover("", "", List::of, classpath, pathSeparator);
    }

    List<Path> discover(
            String configuredProperty,
            String configuredEnvironment,
            Supplier<List<Path>> bundledWorkerClasspath,
            String currentClasspath,
            String pathSeparator) {
        List<Path> propertyEntries = classpathEntries(configuredProperty, pathSeparator);
        if (!propertyEntries.isEmpty()) {
            return propertyEntries;
        }
        List<Path> environmentEntries = classpathEntries(configuredEnvironment, pathSeparator);
        if (!environmentEntries.isEmpty()) {
            return environmentEntries;
        }
        List<Path> bundledEntries = bundledWorkerClasspath == null ? List.of() : bundledWorkerClasspath.get();
        if (!bundledEntries.isEmpty()) {
            return bundledEntries.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        }
        List<Path> entries = classpathEntries(currentClasspath, pathSeparator);
        if (entries.isEmpty()) {
            throw new TestRunException(
                    "Could not determine Zolt worker classpath for test execution. "
                            + "Run zolt test from a packaged launcher, set "
                            + PROPERTY
                            + " or "
                            + ENVIRONMENT
                            + ", or check java.class.path.");
        }
        return entries;
    }

    private List<Path> bundledWorkerClasspath() {
        Optional<Path> executable = ProcessHandle.current().info().command().map(Path::of);
        if (executable.isEmpty()) {
            return List.of();
        }
        Path command = executable.orElseThrow().toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>();
        Path parent = command.getParent();
        if (parent != null) {
            candidates.add(parent.resolve("libexec").resolve(BUNDLED_WORKER_JAR));
            Path installRoot = parent.getParent();
            if (installRoot != null && "bin".equals(parent.getFileName().toString())) {
                candidates.add(installRoot.resolve("libexec").resolve(BUNDLED_WORKER_JAR));
            }
        }
        return candidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .distinct()
                .toList();
    }

    private static List<Path> classpathEntries(String classpath, String pathSeparator) {
        String effectiveClasspath = classpath == null ? "" : classpath;
        String effectiveSeparator = pathSeparator == null || pathSeparator.isEmpty()
                ? java.io.File.pathSeparator
                : pathSeparator;
        return Arrays.stream(effectiveClasspath.split(java.util.regex.Pattern.quote(effectiveSeparator)))
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
    }
}
