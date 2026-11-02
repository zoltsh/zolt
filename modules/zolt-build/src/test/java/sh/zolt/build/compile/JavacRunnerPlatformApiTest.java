package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.JavacException;
import sh.zolt.classpath.Classpath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** : verifies {@code --release} vs host {@code -source/-target} flag emission and semantics. */
final class JavacRunnerPlatformApiTest {
    @TempDir
    private Path tempDir;

    @Test
    void releaseModeEmitsReleaseFlag() throws IOException {
        List<List<String>> commands = captureCommand(new JavacOptions("8", "", List.of(), List.of(), false));

        assertTrue(commands.getFirst().contains("--release"), commands.getFirst().toString());
        assertFalse(commands.getFirst().contains("-source"), commands.getFirst().toString());
        assertFalse(commands.getFirst().contains("-target"), commands.getFirst().toString());
    }

    @Test
    void hostModeEmitsSourceAndTargetFlags() throws IOException {
        List<List<String>> commands = captureCommand(new JavacOptions("8", "", List.of(), List.of(), true));
        List<String> command = commands.getFirst();

        assertFalse(command.contains("--release"), command.toString());
        assertEquals("-source", command.get(command.indexOf("-source")));
        assertEquals("8", command.get(command.indexOf("-source") + 1));
        assertEquals("8", command.get(command.indexOf("-target") + 1));
    }

    @Test
    void selectedJdkEightModeUsesSourceAndTargetWithoutClaimingHostApi() throws IOException {
        JavacOptions options = new JavacOptions(
                "8", "", List.of(), List.of(), false, false);

        List<String> command = captureCommand(options).getFirst();

        assertFalse(options.hostPlatformApi());
        assertFalse(command.contains("--release"), command.toString());
        assertEquals("8", command.get(command.indexOf("-source") + 1));
        assertEquals("8", command.get(command.indexOf("-target") + 1));
    }

    @Test
    void rejectsContradictoryHostAndReleaseModes() {
        JavacException exception = assertThrows(
                JavacException.class,
                () -> new JavacOptions("8", "", List.of(), List.of(), true, true));

        assertTrue(exception.getMessage().contains("cannot be combined"));
    }

    @Test
    void releaseEightRejectsPost8PlatformApi() throws IOException {
        Path source = source("src/main/java/com/example/Main.java", post8ApiSource());

        JavacException exception = assertThrows(JavacException.class, () -> new JavacRunner().compile(
                currentJavac(),
                List.of(source),
                new Classpath(List.of()),
                tempDir.resolve("target/classes"),
                new Classpath(List.of()),
                null,
                new JavacOptions("8", "", List.of(), List.of(), false)));

        assertTrue(exception.getMessage().contains("javac failed"), exception.getMessage());
    }

    @Test
    void hostModeCompilesPost8PlatformApiAtJavaEight() throws IOException {
        Path source = source("src/main/java/com/example/Main.java", post8ApiSource());

        JavacResult result = new JavacRunner().compile(
                currentJavac(),
                List.of(source),
                new Classpath(List.of()),
                tempDir.resolve("target/classes"),
                new Classpath(List.of()),
                null,
                new JavacOptions("8", "", List.of(), List.of(), true));

        assertEquals(1, result.sourceCount());
        assertTrue(Files.exists(tempDir.resolve("target/classes/com/example/Main.class")));
    }

    private static String post8ApiSource() {
        // String.isBlank() is a Java 11 platform API: rejected by --release 8 (ct.sym), but
        // compilable under -source 8 -target 8 against a modern host JDK's platform API.
        return """
                package com.example;

                public final class Main {
                    public static boolean blank(String value) {
                        return value.isBlank();
                    }
                }
                """;
    }

    private List<List<String>> captureCommand(JavacOptions options) throws IOException {
        Path source = source("src/main/java/com/example/Main.java", "final class Main {}\n");
        List<List<String>> commands = new ArrayList<>();
        JavacRunner runner = new JavacRunner(":", command -> {
            commands.add(command);
            return new JavacRunner.ProcessResult(0, "");
        });
        runner.compile(
                Path.of("javac"),
                List.of(source),
                new Classpath(List.of()),
                tempDir.resolve("target/classes"),
                new Classpath(List.of()),
                null,
                options);
        return commands;
    }

    private Path source(String path, String content) throws IOException {
        Path source = tempDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }

    private static Path currentJavac() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "javac.exe" : "javac";
        return Path.of(System.getProperty("java.home")).resolve("bin").resolve(executable);
    }
}
