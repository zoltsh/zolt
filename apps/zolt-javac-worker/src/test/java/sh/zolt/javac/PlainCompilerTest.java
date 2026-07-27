package sh.zolt.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PlainCompilerTest {
    @TempDir
    private Path tempDir;

    @Test
    void reusesStandardFileManagerAcrossSequentialRequests() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        AtomicInteger fileManagerCreations = new AtomicInteger();
        PlainCompiler plainCompiler = new PlainCompiler(
                compiler,
                () -> {
                    fileManagerCreations.incrementAndGet();
                    return compiler.getStandardFileManager(
                            null,
                            null,
                            StandardCharsets.UTF_8);
                });
        Path firstSource = source("First");
        Path secondSource = source("Second");
        Path firstOutput = Files.createDirectories(tempDir.resolve("first-classes"));
        Path secondOutput = Files.createDirectories(tempDir.resolve("second-classes"));

        PlainCompiler.Result first = plainCompiler.compile(List.of(
                "-proc:none",
                "-d",
                firstOutput.toString(),
                firstSource.toString()));
        PlainCompiler.Result second = plainCompiler.compile(List.of(
                "-proc:none",
                "-d",
                secondOutput.toString(),
                secondSource.toString()));

        assertEquals(0, first.exitCode(), first.diagnostics());
        assertEquals(0, second.exitCode(), second.diagnostics());
        assertEquals(1, fileManagerCreations.get());
        assertTrue(Files.isRegularFile(firstOutput.resolve("First.class")));
        assertTrue(Files.isRegularFile(secondOutput.resolve("Second.class")));
    }

    @Test
    void doesNotLeakClasspathBetweenSequentialRequests() throws Exception {
        PlainCompiler plainCompiler = new PlainCompiler();
        Path librarySource = source("Library");
        Path consumerSource = tempDir.resolve("Consumer.java");
        Path isolatedSource = tempDir.resolve("Isolated.java");
        Files.writeString(consumerSource, "public class Consumer { Library value; }\n");
        Files.writeString(isolatedSource, "public class Isolated { Library value; }\n");
        Path libraryOutput = Files.createDirectories(tempDir.resolve("library-classes"));
        Path consumerOutput = Files.createDirectories(tempDir.resolve("consumer-classes"));
        Path isolatedOutput = Files.createDirectories(tempDir.resolve("isolated-classes"));

        PlainCompiler.Result library = plainCompiler.compile(List.of(
                "-proc:none",
                "-d",
                libraryOutput.toString(),
                librarySource.toString()));
        PlainCompiler.Result consumer = plainCompiler.compile(List.of(
                "-proc:none",
                "-classpath",
                libraryOutput.toString(),
                "-d",
                consumerOutput.toString(),
                consumerSource.toString()));
        PlainCompiler.Result isolated = plainCompiler.compile(List.of(
                "-proc:none",
                "-d",
                isolatedOutput.toString(),
                isolatedSource.toString()));

        assertEquals(0, library.exitCode(), library.diagnostics());
        assertEquals(0, consumer.exitCode(), consumer.diagnostics());
        assertEquals(1, isolated.exitCode(), isolated.diagnostics());
    }

    private Path source(String name) throws Exception {
        Path source = tempDir.resolve(name + ".java");
        Files.writeString(source, "public class " + name + " {}\n");
        return source;
    }
}
