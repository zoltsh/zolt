package sh.zolt.build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Compiles the real {@code apps/zolt-javac-worker} sources into a jar and points the compile pipeline
 * at it via {@code zolt.javac.worker.classpath}, so tests can exercise the true worker attribution
 * path (there is no worker bundled next to the test JVM). The non-persistent transport is used to keep
 * the worker process lifecycle contained within the test.
 */
final class WorkerAttributionTestSupport implements AutoCloseable {
    private final String previousClasspath;
    private final String previousPersistent;

    private WorkerAttributionTestSupport(Path workerJar) {
        this.previousClasspath = System.getProperty("zolt.javac.worker.classpath");
        this.previousPersistent = System.getProperty("zolt.javac.worker.persistent");
        System.setProperty("zolt.javac.worker.classpath", workerJar.toString());
        System.setProperty("zolt.javac.worker.persistent", "false");
    }

    static WorkerAttributionTestSupport enable(Path workDirectory) throws Exception {
        return new WorkerAttributionTestSupport(buildWorkerJar(workDirectory));
    }

    @Override
    public void close() {
        restore("zolt.javac.worker.classpath", previousClasspath);
        restore("zolt.javac.worker.persistent", previousPersistent);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static Path buildWorkerJar(Path workDirectory) throws Exception {
        Path workerSources = repositoryRoot().resolve("apps/zolt-javac-worker/src/main/java");
        Path classes = Files.createDirectories(workDirectory.resolve("worker-classes"));
        List<String> sources;
        try (Stream<Path> paths = Files.walk(workerSources)) {
            sources = paths.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        List<String> arguments = new java.util.ArrayList<>(
                List.of("--release", "21", "-proc:none", "-d", classes.toString()));
        arguments.addAll(sources);
        int exitCode = compiler.run(null, diagnostics, diagnostics, arguments.toArray(String[]::new));
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "Could not compile the javac worker for tests: " + diagnostics.toString(StandardCharsets.UTF_8));
        }
        Path jar = workDirectory.resolve("zolt-javac-worker.jar");
        writeJar(classes, jar);
        return jar;
    }

    private static Path repositoryRoot() {
        for (Path candidate : searchRoots()) {
            Path directory = candidate;
            while (directory != null) {
                if (Files.isRegularFile(directory.resolve(
                        "apps/zolt-javac-worker/src/main/java/sh/zolt/javac/JavacWorkerMain.java"))) {
                    return directory;
                }
                directory = directory.getParent();
            }
        }
        throw new IllegalStateException("Could not locate the repository root from the test working directory.");
    }

    private static List<Path> searchRoots() {
        List<Path> roots = new java.util.ArrayList<>();
        try {
            roots.add(Path.of(WorkerAttributionTestSupport.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize());
        } catch (RuntimeException | java.net.URISyntaxException ignored) {
            // Fall back to the working directory below.
        }
        roots.add(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize());
        return roots;
    }

    private static void writeJar(Path root, Path jar) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
                Stream<Path> paths = Files.walk(root)) {
            List<Path> files = paths.filter(Files::isRegularFile).sorted().toList();
            for (Path file : files) {
                JarEntry entry = new JarEntry(root.relativize(file).toString().replace(File.separatorChar, '/'));
                output.putNextEntry(entry);
                output.write(Files.readAllBytes(file));
                output.closeEntry();
            }
        }
    }
}
