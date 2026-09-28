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
    static final String JUPITER_VERSION = "5.14.4";

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

    /** Publishes the coordinates emitted and injected for a fresh {@code zolt init} project. */
    static void publishInitProject(CliTestRepository repository) throws IOException {
        byte[] consoleJar = Files.readAllBytes(jar());
        repository.addArtifact(
                "org.junit.jupiter",
                "junit-jupiter",
                JUPITER_VERSION,
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>%s</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter-api</artifactId>
                      <version>%s</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(JUPITER_VERSION, JUPITER_VERSION));
        repository.addArtifact(
                "org.junit.jupiter",
                "junit-jupiter-api",
                JUPITER_VERSION,
                pom("org.junit.jupiter", "junit-jupiter-api", JUPITER_VERSION),
                consoleJar);
        repository.addArtifact(
                "org.junit.platform",
                "junit-platform-console",
                VERSION,
                pom("org.junit.platform", "junit-platform-console", VERSION),
                consoleJar);
    }

    private static String pom(String group, String artifact, String version) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(group, artifact, version);
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
