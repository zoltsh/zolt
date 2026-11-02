package sh.zolt.build.compile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

final class JavacWorkerClasspath {
    static final String PROPERTY = "zolt.javac.worker.classpath";
    static final String ENVIRONMENT = "ZOLT_JAVAC_WORKER_CLASSPATH";
    static final String BUNDLED_WORKER_JAR = "zolt-javac-worker.jar";

    private JavacWorkerClasspath() {
    }

    static Optional<Path> discover() {
        Optional<Path> configured = configuredPath(System.getProperty(PROPERTY));
        if (configured.isPresent()) {
            return configured;
        }
        configured = configuredPath(System.getenv(ENVIRONMENT));
        if (configured.isPresent()) {
            return configured;
        }
        return ProcessHandle.current().info().command().map(Path::of).flatMap(JavacWorkerClasspath::bundledWorker);
    }

    private static Optional<Path> configuredPath(String configured) {
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }
        Path path = Path.of(configured).toAbsolutePath().normalize();
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    static Optional<Path> bundledWorker(Path executable) {
        try {
            // macOS can report the installed launcher symlink instead of its versioned target.
            Path command = executable.toRealPath();
            Path parent = command.getParent();
            if (parent == null || !Files.isRegularFile(command)) return Optional.empty();
            Path adjacent = parent.resolve("libexec").resolve(BUNDLED_WORKER_JAR);
            if (Files.isRegularFile(adjacent)) return Optional.of(adjacent);
            Path installRoot = parent.getParent();
            if (installRoot == null || !"bin".equals(parent.getFileName().toString())) return Optional.empty();
            Path bundled = installRoot.resolve("libexec").resolve(BUNDLED_WORKER_JAR);
            return Files.isRegularFile(bundled) ? Optional.of(bundled) : Optional.empty();
        } catch (IOException exception) {
            return Optional.empty(); // Executable discovery is optional; retain the external javac fallback.
        }
    }
}
