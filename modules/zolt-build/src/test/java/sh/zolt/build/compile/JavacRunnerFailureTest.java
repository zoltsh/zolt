package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.JavacException;
import sh.zolt.classpath.Classpath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JavacRunnerFailureTest {
    @TempDir
    private Path tempDir;

    @Test
    void surfacesJavacErrorsWithCompilerOutput() throws IOException {
        Path source = source("src/main/java/com/example/Broken.java", """
                package com.example;

                public final class Broken {
                    missing
                }
                """);

        JavacException exception = assertThrows(
                JavacException.class,
                () -> new JavacRunner().compile(
                        currentJavac(),
                        List.of(source),
                        new Classpath(List.of()),
                        tempDir.resolve("target/classes")));

        assertTrue(exception.getMessage().contains("javac failed with exit code"));
        assertTrue(exception.getMessage().contains("Broken.java"));
        assertTrue(exception.getMessage().contains("[dependencies.processor]"));
        assertFalse(exception.getMessage().isBlank());
    }

    @Test
    void missingDependencyFailureOmitsAnnotationProcessorHint() throws IOException {
        Path source = source("src/main/java/com/example/UsesMissing.java", """
                package com.example;

                import com.nowhere.Absent;

                public final class UsesMissing {
                    Absent value;
                }
                """);

        JavacException exception = assertThrows(
                JavacException.class,
                () -> new JavacRunner().compile(
                        currentJavac(),
                        List.of(source),
                        new Classpath(List.of()),
                        tempDir.resolve("target/classes")));

        assertTrue(exception.getMessage().contains("javac failed with exit code"));
        assertTrue(exception.getMessage().contains("package com.nowhere does not exist"));
        assertFalse(
                exception.getMessage().contains("[dependencies.processor]"),
                "missing-dependency failures must not red-herring annotation processing");
        assertTrue(exception.getMessage().contains("dependency"));
    }

    private Path source(String path, String content) throws IOException {
        Path source = tempDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }

    private static Path currentJavac() {
        return Path.of(System.getProperty("java.home")).resolve("bin").resolve(executable("javac"));
    }

    private static String executable(String name) {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? name + ".exe"
                : name;
    }
}
