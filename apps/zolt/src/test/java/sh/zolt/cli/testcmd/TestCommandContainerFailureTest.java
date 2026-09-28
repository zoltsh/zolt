package sh.zolt.cli.testcmd;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.classpath.Classpath;
import sh.zolt.build.compile.JavacRunner;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.error.WorkerFailureDiagnostic;
import sh.zolt.test.TestSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated("mutates the JUnit worker classpath system property")
final class TestCommandContainerFailureTest extends TestCommandTestSupport {
    private static final String WORKER_CLASSPATH_PROPERTY = "zolt.junit.worker.classpath";

    @TempDir
    private Path tempDir;

    @Test
    void testCommandReturnsNonZeroForRealAfterAllContainerFailure() throws Exception {
        Path projectDir = tempDir.resolve("lifecycle-failure");
        Path cacheRoot = tempDir.resolve("cache");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("lifecycle-failure"));
        seedJUnitConsole(cacheRoot);
        writeJUnitConsoleLockfile(projectDir, cacheRoot);
        Path testSource = projectDir.resolve("src/test/java/com/example/LifecycleFailureTest.java");
        Files.createDirectories(testSource.getParent());
        Files.writeString(testSource, """
                package com.example;

                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.Test;

                final class LifecycleFailureTest {
                    @Test
                    void passingTest() {
                    }

                    @AfterAll
                    static void brokenTeardown() {
                        throw new IllegalStateException("teardown failed");
                    }
                }
                """);
        String previous = System.getProperty(WORKER_CLASSPATH_PROPERTY);
        System.setProperty(WORKER_CLASSPATH_PROPERTY, compiledWorkerClasspath());
        CommandResult result;
        try {
            result = execute(
                    "test",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cacheRoot.toString());
        } finally {
            restoreProperty(previous);
        }

        assertEquals(1, result.exitCode(), result.stderr());
        assertTrue(result.stderr().contains("JUnit worker tests failed with exit code 1"), result.stderr());
        assertTrue(result.stderr().contains("teardown failed"), result.stderr());
    }

    private String compiledWorkerClasspath() throws Exception {
        Path repository = repositoryRoot();
        Path sourceRoot = repository.resolve("apps/zolt-junit-worker/src/main/java");
        List<Path> sources;
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            sources = paths.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
        List<Path> runtime = runtimeClasspath();
        Path output = tempDir.resolve("worker-classes");
        new JavacRunner().compile(currentJavac(), sources, new Classpath(runtime), output);
        List<Path> worker = new ArrayList<>();
        worker.add(output);
        worker.addAll(runtime);
        return worker.stream()
                .map(path -> path.toAbsolutePath().normalize().toString())
                .distinct()
                .collect(Collectors.joining(java.io.File.pathSeparator));
    }

    private static List<Path> runtimeClasspath() throws Exception {
        List<Path> entries = new ArrayList<>(Arrays.stream(System.getProperty("java.class.path", "")
                        .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .toList());
        entries.add(codeSource(WorkerFailureDiagnostic.class));
        entries.add(codeSource(TestSelection.class));
        entries.add(codeSource(Class.forName("sh.zolt.junit.JunitWorkerProtocol")));
        entries.add(codeSource(org.junit.jupiter.api.Test.class));
        return entries.stream().distinct().toList();
    }

    private static void seedJUnitConsole(Path cacheRoot) throws Exception {
        Path source = codeSource(org.junit.jupiter.api.Test.class);
        Path destination = cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar");
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path codeSource(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath()
                .normalize();
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("zolt.toml"))
                    && Files.isDirectory(current.resolve("apps/zolt-junit-worker/src/main/java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the Zolt repository root.");
    }

    private static Path currentJavac() {
        return Path.of(System.getProperty("java.home"), "bin", executable("javac"));
    }

    private static String executable(String name) {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? name + ".exe"
                : name;
    }

    private static void restoreProperty(String previous) {
        if (previous == null) {
            System.clearProperty(WORKER_CLASSPATH_PROPERTY);
        } else {
            System.setProperty(WORKER_CLASSPATH_PROPERTY, previous);
        }
    }
}
