package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cancel.BuildCancellation;
import sh.zolt.classpath.Classpath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JavacRunnerInProcessTest {
    @TempDir
    private Path tempDir;

    @Test
    void usesInProcessCompilerForRuntimeJavacWithoutProcessors() throws IOException {
        Path source = source("src/main/java/com/example/Main.java", "final class Main {}\n");
        List<List<String>> inProcessArguments = new ArrayList<>();
        List<List<String>> externalCommands = new ArrayList<>();
        JavacRunner runner = runner(externalCommands, inProcessArguments, Path.of("javac"));

        JavacResult result = runner.compile(
                Path.of("javac"),
                List.of(source),
                new Classpath(List.of()),
                tempDir.resolve("target/classes"));

        assertEquals(1, result.sourceCount());
        assertTrue(externalCommands.isEmpty());
        assertEquals(List.of(
                "-d",
                tempDir.resolve("target/classes").toString(),
                "-proc:none",
                source.normalize().toString()), inProcessArguments.getFirst());
    }

    @Test
    void usesExternalJavacWhenProcessorsAreConfigured() throws IOException {
        Path source = source("src/main/java/com/example/Main.java", "final class Main {}\n");
        List<List<String>> inProcessArguments = new ArrayList<>();
        List<List<String>> externalCommands = new ArrayList<>();
        JavacRunner runner = runner(externalCommands, inProcessArguments, Path.of("javac"));

        runner.compile(
                Path.of("javac"),
                List.of(source),
                new Classpath(List.of()),
                tempDir.resolve("target/classes"),
                new Classpath(List.of(Path.of("processor.jar"))),
                tempDir.resolve("target/generated/sources/annotations"));

        assertTrue(inProcessArguments.isEmpty());
        assertEquals("javac", externalCommands.getFirst().getFirst());
    }

    @Test
    void usesExternalJavacWhenRequestedJavacDiffersFromRuntimeJavac() throws IOException {
        Path source = source("src/main/java/com/example/Main.java", "final class Main {}\n");
        List<List<String>> inProcessArguments = new ArrayList<>();
        List<List<String>> externalCommands = new ArrayList<>();
        JavacRunner runner = runner(externalCommands, inProcessArguments, Path.of("runtime-javac"));

        runner.compile(
                Path.of("managed-javac"),
                List.of(source),
                new Classpath(List.of()),
                tempDir.resolve("target/classes"));

        assertTrue(inProcessArguments.isEmpty());
        assertEquals("managed-javac", externalCommands.getFirst().getFirst());
    }

    @Test
    void usesExternalJavacWhenRuntimeJavacIsUnavailable() throws IOException {
        Path source = source("src/main/java/com/example/Main.java", "final class Main {}\n");
        List<List<String>> inProcessArguments = new ArrayList<>();
        List<List<String>> externalCommands = new ArrayList<>();
        JavacRunner runner = runner(externalCommands, inProcessArguments, null);

        runner.compile(
                Path.of("managed-javac"),
                List.of(source),
                new Classpath(List.of()),
                tempDir.resolve("target/classes"));

        assertTrue(inProcessArguments.isEmpty());
        assertEquals("managed-javac", externalCommands.getFirst().getFirst());
    }

    @Test
    void isolatesConcurrentBuildCompilationFromTheCallingJvm() throws IOException {
        Path source = source("src/main/java/com/example/Main.java", "final class Main {}\n");
        List<List<String>> inProcessArguments = new ArrayList<>();
        List<List<String>> externalCommands = new ArrayList<>();
        JavacRunner runner = runner(externalCommands, inProcessArguments, Path.of("javac"));

        new BuildCancellation().call(() -> {
            runner.compile(
                    Path.of("javac"),
                    List.of(source),
                    new Classpath(List.of()),
                    tempDir.resolve("target/classes"));
            return null;
        });

        assertTrue(inProcessArguments.isEmpty());
        assertEquals("javac", externalCommands.getFirst().getFirst());
    }

    @Test
    void runtimeJavacIsUnavailableWithoutJavaHome() {
        assertNull(JavacRunner.runtimeJavac(null, null));
    }

    @Test
    void runtimeJavacIsUnavailableInsideNativeImage() {
        assertNull(JavacRunner.runtimeJavac("/runtime-jdk", "runtime"));
    }

    @Test
    void usesExternalJavacWhenLauncherArgumentsAreConfigured() throws IOException {
        Path source = source("src/main/java/com/example/Main.java", "final class Main {}\n");
        List<List<String>> inProcessArguments = new ArrayList<>();
        List<List<String>> externalCommands = new ArrayList<>();
        JavacRunner runner = runner(externalCommands, inProcessArguments, Path.of("javac"));

        runner.compile(
                Path.of("javac"),
                List.of(source),
                new Classpath(List.of()),
                tempDir.resolve("target/classes"),
                new Classpath(List.of()),
                null,
                new JavacOptions("", "", List.of("-J-Xmx256m")));

        assertTrue(inProcessArguments.isEmpty());
        assertTrue(externalCommands.getFirst().contains("-J-Xmx256m"));
    }

    private JavacRunner runner(
            List<List<String>> externalCommands,
            List<List<String>> inProcessArguments,
            Path runtimeJavac) {
        return new JavacRunner(
                ":",
                command -> {
                    externalCommands.add(command);
                    return new JavacRunner.ProcessResult(0, "");
                },
                arguments -> {
                    inProcessArguments.add(arguments);
                    return new JavacRunner.ProcessResult(0, "");
                },
                runtimeJavac);
    }

    private Path source(String path, String content) throws IOException {
        Path source = tempDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }
}
