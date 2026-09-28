package sh.zolt.cli.build;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.platform.console.ConsoleLauncher;
import sh.zolt.cli.CliTestRepository;

/** Publishes the real JUnit console used by CLI integration tests. */
final class JUnitConsoleCliFixture {
    static final String VERSION = "1.14.4";

    private JUnitConsoleCliFixture() {
    }

    static void publish(CliTestRepository repository) throws IOException {
        repository.addArtifact(
                "org.junit.platform",
                "junit-platform-console-standalone",
                VERSION,
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.junit.platform</groupId>
                  <artifactId>junit-platform-console-standalone</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(VERSION),
                Files.readAllBytes(jar()));
    }

    private static Path jar() {
        try {
            Path location = Path.of(ConsoleLauncher.class.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toAbsolutePath()
                    .normalize();
            if (!Files.isRegularFile(location) || !location.getFileName().toString().endsWith(".jar")) {
                throw new IllegalStateException("JUnit console is not loaded from a JAR: " + location);
            }
            return location;
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Could not locate the JUnit console JAR.", exception);
        }
    }
}
