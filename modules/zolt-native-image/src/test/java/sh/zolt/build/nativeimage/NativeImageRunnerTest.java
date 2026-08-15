package sh.zolt.build.nativeimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.NativeImageException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeImageRunnerTest {
    @TempDir
    private Path tempDir;

    @Test
    void passesJarRuntimeDependenciesMainClassAndOutputToNativeImage() throws IOException {
        List<List<String>> commands = new ArrayList<>();
        Path outputBinary = tempDir.resolve("target/native/demo");
        Path logFile = tempDir.resolve("target/native/native-image.log");
        NativeImageRunner runner = new NativeImageRunner(":", command -> {
            commands.add(command);
            writeNativeBinary(Path.of(command.getLast()), "native");
            return new NativeImageRunner.ProcessResult(0, "native ok\n");
        });

        NativeImageResult result = runner.build(new NativeImageRequest(
                Path.of("native-image"),
                Path.of("target/demo.jar"),
                List.of(Path.of("cache/lib.jar")),
                "com.example.Main",
                outputBinary,
                logFile,
                List.of("--no-fallback", "--native-image-info")));

        assertEquals(outputBinary, result.outputBinary());
        assertEquals(logFile, result.logFile());
        assertEquals("native ok\n", result.output());
        assertEquals("native ok\n", Files.readString(logFile));
        assertEquals("native", Files.readString(outputBinary));
        assertEquals(List.of(
                "native-image",
                "--no-fallback",
                "--native-image-info",
                "-cp",
                "target/demo.jar:cache/lib.jar",
                "com.example.Main",
                "-o"), commands.getFirst().subList(0, 7));
        assertTrue(commands.getFirst().getLast().startsWith(
                outputBinary.getParent().resolve(".demo.zolt-staging-").toString()));
    }

    @Test
    void nullExecutableUsesNativeImageFromPath() {
        List<List<String>> commands = new ArrayList<>();
        Path outputBinary = tempDir.resolve("demo");
        NativeImageRunner runner = new NativeImageRunner(":", command -> {
            commands.add(command);
            writeNativeBinary(Path.of(command.getLast()), "native");
            return new NativeImageRunner.ProcessResult(0, "");
        });

        runner.build(new NativeImageRequest(
                null,
                Path.of("target/demo.jar"),
                List.of(),
                "com.example.Main",
                outputBinary,
                tempDir.resolve("native-image.log"),
                List.of()));

        assertEquals("native-image", commands.getFirst().getFirst());
    }

    @Test
    void forwardsProgressHeartbeatToNativeImageProcessExecution() throws IOException {
        List<String> progressEvents = new ArrayList<>();
        Path outputBinary = tempDir.resolve("target/native/demo");
        NativeImageRunner runner = new NativeImageRunner(":", (command, progress, output) -> {
            progress.run();
            writeNativeBinary(Path.of(command.getLast()), "native");
            output.accept("native ok\n");
            return new NativeImageRunner.ProcessResult(0, "native ok\n");
        });

        NativeImageResult result = runner.build(
                new NativeImageRequest(
                        Path.of("native-image"),
                        Path.of("target/demo.jar"),
                        List.of(),
                        "com.example.Main",
                        outputBinary,
                        tempDir.resolve("target/native/native-image.log"),
                        List.of()),
                () -> progressEvents.add("Still running: Native Image"));

        assertEquals(outputBinary, result.outputBinary());
        assertEquals(List.of("Still running: Native Image"), progressEvents);
    }

    @Test
    void preservesExistingOutputBinaryUntilAtomicPublication() throws IOException {
        Path outputBinary = tempDir.resolve("target/native/demo");
        Files.createDirectories(outputBinary.getParent());
        Files.writeString(outputBinary, "stale");
        NativeImageRunner runner = new NativeImageRunner(":", command -> {
            assertEquals("stale", readString(outputBinary));
            writeNativeBinary(Path.of(command.getLast()), "fresh");
            return new NativeImageRunner.ProcessResult(0, "native ok\n");
        });

        runner.build(new NativeImageRequest(
                Path.of("native-image"),
                Path.of("target/demo.jar"),
                List.of(),
                "com.example.Main",
                outputBinary,
                tempDir.resolve("target/native/native-image.log"),
                List.of()));

        assertEquals("fresh", Files.readString(outputBinary));
    }

    @Test
    void successfulNativeImageMustCreateExpectedOutputBinary() throws IOException {
        NativeImageRunner runner = new NativeImageRunner(":", command ->
                new NativeImageRunner.ProcessResult(0, "native ok\n"));
        Path outputBinary = tempDir.resolve("target/native/demo");
        Path logFile = tempDir.resolve("target/native/native-image.log");

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> runner.build(new NativeImageRequest(
                        Path.of("native-image"),
                        Path.of("target/demo.jar"),
                        List.of(),
                        "com.example.Main",
                        outputBinary,
                        logFile,
                        List.of("--no-fallback"))));

        assertTrue(exception.getMessage().contains("did not create expected binary"));
        assertTrue(exception.getMessage().contains(outputBinary.toString()));
        assertEquals("native ok\n", Files.readString(logFile));
    }

    @Test
    void nonZeroExitWritesLogAndReturnsActionableError() throws IOException {
        NativeImageRunner runner = new NativeImageRunner(":", command ->
                new NativeImageRunner.ProcessResult(3, "missing reflection config\n"));
        Path logFile = tempDir.resolve("target/native/native-image.log");

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> runner.build(new NativeImageRequest(
                        Path.of("native-image"),
                        Path.of("target/demo.jar"),
                        List.of(),
                        "com.example.Main",
                        tempDir.resolve("target/native/demo"),
                        logFile,
                        List.of("--no-fallback"))));

        assertTrue(exception.getMessage().contains("native-image failed with exit code 3"));
        assertTrue(exception.getMessage().contains("Review " + logFile));
        assertTrue(exception.getMessage().contains("missing reflection config"));
        assertEquals("missing reflection config\n", Files.readString(logFile));
    }

    @Test
    void missingMainClassFailsBeforeProcessExecution() {
        NativeImageRunner runner = new NativeImageRunner(":", command -> {
            throw new AssertionError("native-image should not run");
        });

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> runner.build(new NativeImageRequest(
                        Path.of("native-image"),
                        Path.of("target/demo.jar"),
                        List.of(),
                        "",
                        tempDir.resolve("demo"),
                        tempDir.resolve("native-image.log"),
                        List.of())));

        assertTrue(exception.getMessage().contains("Add [project].main"));
    }

    @Test
    void rejectsOnePathForBinaryAndLogBeforeMutationOrProcessExecution() throws IOException {
        Path output = tempDir.resolve("target/native/native-image.log");
        Files.createDirectories(output.getParent());
        Files.writeString(output, "existing");
        NativeImageRunner runner = new NativeImageRunner(":", command -> {
            throw new AssertionError("native-image should not run");
        });

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> runner.build(new NativeImageRequest(
                        Path.of("native-image"),
                        Path.of("target/demo.jar"),
                        List.of(),
                        "com.example.Main",
                        output,
                        output,
                        List.of())));

        assertTrue(exception.getMessage().contains("binary and log must be distinct"));
        assertEquals("existing", Files.readString(output));
    }

    private static void writeNativeBinary(Path outputBinary, String content) {
        try {
            Files.writeString(outputBinary, content);
            outputBinary.toFile().setExecutable(true, false);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
