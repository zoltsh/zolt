package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import sh.zolt.cli.CliTestSupport.CommandResult;

public final class KotlinCliBuildCacheTestSupport {
    private KotlinCliBuildCacheTestSupport() {
    }

    public static void configure(Path userHome) throws IOException {
        Path globalDirectory = userHome.resolve(".zolt");
        Files.createDirectories(globalDirectory);
        Files.writeString(globalDirectory.resolve("config.toml"), """
                version = 1

                [buildCache]
                enabled = true
                dir = "build-cache"
                """);
    }

    static Map<String, String> payload(Path output) throws IOException {
        Map<String, String> snapshot = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(output)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(file -> !file.getFileName().toString().startsWith(".zolt-"))
                    .toList()) {
                snapshot.put(
                        output.relativize(path).toString(),
                        Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
            }
        }
        return Map.copyOf(snapshot);
    }

    public static void deleteTrees(Path... roots) throws IOException {
        for (Path root : roots) {
            deleteTree(root);
        }
    }

    static void assertThreeTestsPassed(CommandResult result) {
        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(
                result.stdout().contains("Integration tests passed for 2 workspace members"),
                result.stdout());
        assertCountPhrase(result.stdout(), 1, "tests found");
        assertCountPhrase(result.stdout(), 2, "tests found");
        assertCountPhrase(result.stdout(), 1, "tests successful");
        assertCountPhrase(result.stdout(), 2, "tests successful");
    }

    static void assertWorkspaceCompilation(CommandResult result, int skipped, int restored) {
        String line = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"run workspace integration-test members\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing workspace integration-test timing in:\n" + result.stderr()));
        assertTrue(line.contains("\"mainCompilationsSkipped\":\"2\""), line);
        assertTrue(line.contains("\"testCompilationsSkipped\":\"" + skipped + "\""), line);
        assertTrue(line.contains("\"testCompilationsRestored\":\"" + restored + "\""), line);
        assertTrue(line.contains("\"testCompilationsExecuted\":\"0\""), line);
    }

    static void assertColdMetadata(Path... outputs) {
        for (Path output : outputs) {
            assertTrue(Files.isRegularFile(output.resolve(".zolt-build-test.fingerprint")));
            assertTrue(Files.isRegularFile(output.resolve(".zolt-incremental-test.state")));
        }
    }

    static void assertRestoredMetadata(Path... outputs) {
        for (Path output : outputs) {
            assertTrue(Files.isRegularFile(output.resolve(".zolt-build-test.fingerprint")));
            assertFalse(Files.exists(output.resolve(".zolt-incremental-test.state")));
        }
    }

    static void assertUnitOutputsAbsent(Path workspace) {
        assertFalse(Files.exists(workspace.resolve("modules/library/target/test-classes")));
        assertFalse(Files.exists(workspace.resolve("apps/application/target/test-classes")));
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private static void assertCountPhrase(String output, int expected, String phrase) {
        assertTrue(output.matches("(?s).*\\b" + expected + " " + phrase + "\\b.*"), output);
    }
}
