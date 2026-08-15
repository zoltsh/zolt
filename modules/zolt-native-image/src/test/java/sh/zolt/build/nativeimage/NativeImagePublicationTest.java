package sh.zolt.build.nativeimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.NativeImageException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeImagePublicationTest {
    @TempDir
    private Path tempDir;

    @Test
    void nonzeroExitWithPartialCandidatePreservesLastKnownGoodBinary() throws IOException {
        Path output = existingBinary("last-known-good");
        NativeImageRunner runner = runner(command -> {
            Files.writeString(Path.of(command.getLast()), "partial");
            return new NativeImageRunner.ProcessResult(7, "native failure\n");
        });

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> runner.build(request(output)));

        assertTrue(exception.getMessage().contains("exit code 7"), exception.getMessage());
        assertEquals("last-known-good", Files.readString(output));
        assertEquals("native failure\n", Files.readString(log()));
        assertNoStagingFiles(output);
    }

    @Test
    void zeroExitWithoutCandidatePreservesLastKnownGoodBinary() throws IOException {
        Path output = existingBinary("last-known-good");
        NativeImageRunner runner = runner(command ->
                new NativeImageRunner.ProcessResult(0, "native claimed success\n"));

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> runner.build(request(output)));

        assertTrue(exception.getMessage().contains("did not create expected binary"), exception.getMessage());
        assertEquals("last-known-good", Files.readString(output));
        assertNoStagingFiles(output);
    }

    @Test
    void rejectedCandidateNeverReplacesOrCreatesFinalBinary() throws IOException {
        Path existing = existingBinary("last-known-good");
        NativeImageRunner runner = successfulRunner("candidate");

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> runner.build(
                        request(existing),
                        () -> {},
                        ignored -> {
                            throw new NativeImageException("warning policy rejected candidate");
                        }));

        assertTrue(exception.getMessage().contains("warning policy"));
        assertEquals("last-known-good", Files.readString(existing));
        assertNoStagingFiles(existing);

        Path absent = tempDir.resolve("other/native/demo");
        assertThrows(
                NativeImageException.class,
                () -> runner.build(
                        request(absent),
                        () -> {},
                        ignored -> {
                            throw new NativeImageException("warning policy rejected candidate");
                        }));
        assertFalse(Files.exists(absent));
        assertNoStagingFiles(absent);
    }

    @Test
    void nonExecutableCandidateIsRejectedWithoutPublication() throws IOException {
        Path output = existingBinary("last-known-good");
        NativeImageRunner runner = runner(command -> {
            Files.writeString(Path.of(command.getLast()), "not executable");
            return new NativeImageRunner.ProcessResult(0, "native ok\n");
        });

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> runner.build(request(output)));

        assertTrue(exception.getMessage().contains("non-executable candidate"), exception.getMessage());
        assertEquals("last-known-good", Files.readString(output));
        assertNoStagingFiles(output);
    }

    @Test
    void successfulCandidateAtomicallyReplacesPriorBinary() throws IOException {
        Path output = existingBinary("last-known-good");

        NativeImageResult result = successfulRunner("fresh").build(request(output));

        assertEquals(output, result.outputBinary());
        assertEquals("fresh", Files.readString(output));
        assertTrue(Files.isExecutable(output));
        assertNoStagingFiles(output);
    }

    private NativeImageRunner runner(ThrowingRunner process) {
        return new NativeImageRunner(":", command -> {
            try {
                return process.run(command);
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
        });
    }

    private NativeImageRunner successfulRunner(String content) {
        return runner(command -> {
            Path staging = Path.of(command.getLast());
            Files.writeString(staging, content);
            staging.toFile().setExecutable(true, false);
            return new NativeImageRunner.ProcessResult(0, "native ok\n");
        });
    }

    private Path existingBinary(String content) throws IOException {
        Path output = tempDir.resolve("target/native/demo");
        Files.createDirectories(output.getParent());
        Files.writeString(output, content);
        output.toFile().setExecutable(true, false);
        return output;
    }

    private NativeImageRequest request(Path output) {
        return new NativeImageRequest(
                Path.of("native-image"),
                Path.of("target/demo.jar"),
                List.of(),
                "com.example.Main",
                output,
                output.resolveSibling("native-image.log"),
                List.of("--no-fallback"));
    }

    private Path log() {
        return tempDir.resolve("target/native/native-image.log");
    }

    private static void assertNoStagingFiles(Path output) throws IOException {
        Path parent = output.getParent();
        if (parent == null || Files.notExists(parent)) {
            return;
        }
        try (var paths = Files.list(parent)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().contains(".zolt-staging-")));
        }
    }

    @FunctionalInterface
    private interface ThrowingRunner {
        NativeImageRunner.ProcessResult run(List<String> command) throws IOException;
    }
}
