package sh.zolt.build.fingerprint;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.JavacException;
import sh.zolt.build.compile.JavacRunner;
import sh.zolt.classpath.Classpath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildFingerprintClasspathOrderTest {
    @TempDir
    private Path tempDir;

    @Test
    void duplicateClassPrecedenceChangesCompilerOutcome() throws IOException {
        Path stringApi = source("string-src/example/Api.java", """
                package example;

                public class Api {
                    public String value() { return "string"; }
                }
                """);
        Path integerApi = source("integer-src/example/Api.java", """
                package example;

                public class Api {
                    public Integer value() { return 1; }
                }
                """);
        Path consumer = source("consumer-src/example/Consumer.java", """
                package example;

                public class Consumer {
                    public String call(Api api) { return api.value(); }
                }
                """);
        Path stringClasses = compile(
                stringApi,
                new Classpath(List.of()),
                tempDir.resolve("string-classes"));
        Path integerClasses = compile(
                integerApi,
                new Classpath(List.of()),
                tempDir.resolve("integer-classes"));

        Path successful = compile(
                consumer,
                new Classpath(List.of(stringClasses, integerClasses)),
                tempDir.resolve("consumer-string-first"));
        JavacException failure = assertThrows(
                JavacException.class,
                () -> compile(
                        consumer,
                        new Classpath(List.of(integerClasses, stringClasses)),
                        tempDir.resolve("consumer-integer-first")));

        assertTrue(Files.isRegularFile(successful.resolve("example/Consumer.class")));
        assertTrue(failure.getMessage().contains("javac failed"), failure.getMessage());
    }

    private Path source(String relativePath, String content) throws IOException {
        Path source = tempDir.resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }

    private static Path compile(Path source, Classpath classpath, Path output) {
        new JavacRunner().compile(currentJavac(), List.of(source), classpath, output);
        return output;
    }

    private static Path currentJavac() {
        String executable = System.getProperty("os.name")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? "javac.exe" : "javac";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }
}
