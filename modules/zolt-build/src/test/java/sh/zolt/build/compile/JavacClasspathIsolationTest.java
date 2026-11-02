package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.*;

import sh.zolt.build.JavacException;
import sh.zolt.classpath.Classpath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JavacClasspathIsolationTest {
    @TempDir Path root;

    @Test
    void rejectsUndeclaredClassesFromWorkingDirectoryAndEnvironment() throws Exception {
        Path ambient = root.resolve("ambient");
        Files.createDirectories(ambient);
        Path ambientSource = ambient.resolve("Ambient.java");
        Files.writeString(ambientSource, "public class Ambient {}\n");
        Path javac = Path.of(System.getProperty("java.home"), "bin", "javac");
        assertEquals(0, new ProcessBuilder(javac.toString(), ambientSource.toString()).start().waitFor());
        Path consumer = root.resolve("Consumer.java");
        Files.writeString(consumer, "public class Consumer extends Ambient {}\n");
        JavacRunner runner = new JavacRunner(java.io.File.pathSeparator, command -> {
            try {
                ProcessBuilder builder = new ProcessBuilder(command).directory(ambient.toFile()).redirectErrorStream(true);
                builder.environment().put("CLASSPATH", ambient.toString());
                Process process = builder.start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                return new JavacRunner.ProcessResult(process.waitFor(), output);
            } catch (IOException | InterruptedException exception) {
                throw new AssertionError(exception);
            }
        });
        Path output = root.resolve("output");

        JavacException failure = assertThrows(JavacException.class,
                () -> runner.compile(javac, List.of(consumer), new Classpath(List.of()), output));

        assertTrue(failure.getMessage().contains("cannot find symbol"), failure.getMessage());
        try (var paths = Files.list(output)) {
            assertEquals(0L, paths.count(), "failed compiles must clean the isolated classpath");
        }
        runner.compile(javac, List.of(consumer), new Classpath(List.of(ambient)), output);
        assertTrue(Files.isRegularFile(output.resolve("Consumer.class")));
    }
}
