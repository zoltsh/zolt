package sh.zolt.cli.command.insight;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * End-to-end check of {@code zolt explain verify} against the committed {@code maven-simple} example.
 * It runs real Maven ({@code dependency:tree}) and Zolt's real resolver, so it is gated on Maven being
 * available: when neither {@code ./mvnw} nor {@code mvn} is on {@code PATH} it skips gracefully
 * (mirroring the keytool {@code Files.isExecutable} + {@code assumeTrue} precedent). When it runs it
 * needs network access to resolve Guava/JUnit, as any real migration verification would.
 */
final class ExplainVerifyEndToEndTest {

    @Test
    void comparesRealMavenAndZoltResolutionForMavenSimple(@TempDir Path tempDir) {
        Optional<Path> mavenSimple = locateMavenSimple();
        assumeTrue(mavenSimple.isPresent(), "examples/migration-explain/maven-simple not found from test cwd");
        Path projectRoot = mavenSimple.orElseThrow();
        assumeTrue(mavenAvailable(projectRoot), "neither ./mvnw nor mvn is available; skipping end-to-end Maven test");

        Path zoltDir = tempDir.resolve("zolt");
        writeMirrorZoltToml(zoltDir);

        StringWriter out = new StringWriter();
        CommandLine commandLine = new CommandLine(new ExplainVerifyCommand());
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute(
                "--directory", projectRoot.toString(),
                "--zolt-dir", zoltDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        String report = out.toString();
        // The command exits 0 when the resolved sets match and non-zero when they differ; both are a
        // valid, successful run of the verifier. What must always hold is that a report was produced.
        assertTrue(exitCode == 0 || exitCode == 1, "unexpected exit code " + exitCode + "; report:\n" + report);
        assertTrue(report.contains("Zolt migration verify"), report);
        assertTrue(report.contains("dev.zolt.examples:maven-simple"), report);
        assertTrue(report.contains("Summary"), report);
    }

    private static void writeMirrorZoltToml(Path zoltDir) {
        String toml = """
                [project]
                name = "maven-simple"
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

    private static Optional<Path> locateMavenSimple() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve("examples/migration-explain/maven-simple");
            if (Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return Optional.of(candidate);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private static boolean mavenAvailable(Path projectRoot) {
        if (Files.isExecutable(projectRoot.resolve("mvnw")) || Files.isRegularFile(projectRoot.resolve("mvnw.cmd"))) {
            return true;
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String entry : path.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path directory = Path.of(entry);
            if (Files.isExecutable(directory.resolve("mvn")) || Files.isRegularFile(directory.resolve("mvn.cmd"))) {
                return true;
            }
        }
        return false;
    }
}
