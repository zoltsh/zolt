package sh.zolt.cli.command.insight;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * End-to-end check of {@code zolt explain verify} against the committed Gradle examples, running real
 * Gradle ({@code dependencies}) and Zolt's real resolver. Gated on Gradle being available: when neither
 * a wrapper nor {@code gradle} on {@code PATH} exists it skips gracefully (mirroring the Maven
 * end-to-end's {@code Files.isExecutable} + {@code assumeTrue} precedent). Examples are copied to a temp
 * directory (and their wrappers stripped, so the installed {@code gradle} is used) to keep the worktree
 * clean.
 */
final class ExplainVerifyGradleEndToEndTest {

    @Test
    void comparesRealGradleAndZoltResolutionForGradleSimple(@TempDir Path tempDir) {
        Optional<Path> example = locateExample("gradle-simple");
        assumeTrue(example.isPresent(), "examples/migration-explain/gradle-simple not found from test cwd");
        assumeTrue(gradleOnPath(), "gradle is not on PATH; skipping end-to-end Gradle test");

        Path projectRoot = copyWithoutWrapper(example.orElseThrow(), tempDir.resolve("gradle-simple"));
        appendLines(projectRoot.resolve("build.gradle"), "\ngroup = 'dev.zolt.examples'\nversion = '1.0.0'\n");
        Path zoltDir = tempDir.resolve("zolt");
        writeMirrorZoltToml(zoltDir);

        StringWriter out = new StringWriter();
        int exitCode = run(out, new StringWriter(),
                "--source", "gradle",
                "--directory", projectRoot.toString(),
                "--zolt-dir", zoltDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        String report = out.toString();
        // Exit is 0 on an identical resolution and 1 on differences (Gradle Module Metadata can resolve
        // Guava's transitives differently than Zolt's POM-based resolver); both are a valid run.
        assertTrue(exitCode == 0 || exitCode == 1, "unexpected exit code " + exitCode + "; report:\n" + report);
        assertTrue(report.contains("Zolt migration verify: Gradle vs Zolt"), report);
        assertTrue(report.contains("dev.zolt.examples:gradle-simple"), report);
        assertTrue(report.contains("Summary"), report);
    }

    @Test
    void surfacesCleanFailureWhenGradleCannotResolve(@TempDir Path tempDir) {
        Optional<Path> example = locateExample("gradle-multiproject");
        assumeTrue(example.isPresent(), "examples/migration-explain/gradle-multiproject not found from test cwd");
        assumeTrue(gradleOnPath(), "gradle is not on PATH; skipping end-to-end Gradle test");

        Path projectRoot = copyWithoutWrapper(example.orElseThrow(), tempDir.resolve("gradle-multiproject"));

        StringWriter err = new StringWriter();
        int exitCode = run(new StringWriter(), err,
                "--source", "gradle",
                "--directory", projectRoot.toString(),
                "--zolt-dir", projectRoot.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        // The example includes a non-existent build ('build-logic'), so Gradle fails at configuration
        // time; the verifier must surface that as a clean, non-zero, actionable failure rather than an
        // empty or fabricated comparison.
        String errors = err.toString();
        assertNotEquals(0, exitCode, "expected a non-zero exit for an unresolvable Gradle build");
        assertTrue(errors.toLowerCase().contains("gradle"), "error should mention Gradle: " + errors);
    }

    private static int run(StringWriter out, StringWriter err, String... args) {
        CommandLine commandLine = new CommandLine(new ExplainVerifyCommand())
                // Mirror the real CLI (ZoltCli), which accepts lowercase enum values like `--source gradle`.
                .setCaseInsensitiveEnumValuesAllowed(true);
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));
        // The command renders actionable errors itself and throws an already-printed exception; map it
        // to a non-zero exit code (as the real CLI does) instead of letting it propagate out of execute().
        commandLine.setExecutionExceptionHandler((exception, cmd, parseResult) -> 1);
        return commandLine.execute(args);
    }

    private static void writeMirrorZoltToml(Path zoltDir) {
        String toml = """
                [project]
                name = "gradle-simple"
                version = "1.0.0"
                group = "dev.zolt.examples"
                java = 21

                [repositories]
                central = "https://repo.maven.apache.org/maven2"

                [dependencies]
                "com.google.guava:guava" = "33.4.8-jre"

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = "5.11.4"
                """;
        try {
            Files.createDirectories(zoltDir);
            Files.writeString(zoltDir.resolve("zolt.toml"), toml, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write fixture zolt.toml.", exception);
        }
    }

    private static Path copyWithoutWrapper(Path source, Path target) {
        try (Stream<Path> walk = Files.walk(source)) {
            walk.forEach(path -> {
                Path relative = source.relativize(path);
                String name = relative.getFileName() == null ? "" : relative.getFileName().toString();
                if (name.equals("gradlew") || name.equals("gradlew.bat")) {
                    return;
                }
                try {
                    Path destination = target.resolve(relative.toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination);
                    }
                } catch (IOException exception) {
                    throw new UncheckedIOException("Could not copy " + path, exception);
                }
            });
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not copy Gradle example from " + source, exception);
        }
        return target;
    }

    private static void appendLines(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not append to " + path, exception);
        }
    }

    private static Optional<Path> locateExample(String name) {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve("examples/migration-explain/" + name);
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))) {
                return Optional.of(candidate);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private static boolean gradleOnPath() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String entry : path.split(File.pathSeparator)) {
            if (!entry.isBlank() && Files.isExecutable(Path.of(entry).resolve("gradle"))) {
                return true;
            }
        }
        return false;
    }
}
