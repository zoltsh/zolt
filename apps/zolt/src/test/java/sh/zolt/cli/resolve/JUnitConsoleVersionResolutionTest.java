package sh.zolt.cli.resolve;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JUnitConsoleVersionResolutionTest {
    @TempDir
    private Path tempDir;

    @Test
    void declaredJupiterVersionOverridesManagedConsoleVersion() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("example", "junit-platform", "1.0", """
                    <project>
                      <groupId>example</groupId>
                      <artifactId>junit-platform</artifactId>
                      <version>1.0</version>
                      <packaging>pom</packaging>
                      <dependencyManagement>
                        <dependencies>
                          <dependency>
                            <groupId>org.junit.platform</groupId>
                            <artifactId>junit-platform-console</artifactId>
                            <version>1.14.4</version>
                          </dependency>
                        </dependencies>
                      </dependencyManagement>
                    </project>
                    """);
            addJupiterGraph(repository, "5.11.4", "1.11.4");
            addConsole(repository, "1.11.4");
            Files.writeString(tempDir.resolve("zolt.toml"), memberConfig("junit-console-version") + """

                    [repositories]
                    central = false

                    [repositories.test]
                    url = "%s"

                    [platforms]
                    "example:junit-platform" = "1.0"

                    [dependencies.test]
                    "org.junit.jupiter:junit-jupiter" = "5.11.4"
                    """.formatted(repository.baseUri()));

            CommandResult result = execute(
                    "resolve",
                    "--cwd", tempDir.toString(),
                    "--cache-root", tempDir.resolve("cache").toString());

            assertEquals(0, result.exitCode(), () -> result.stderr());
            String lock = Files.readString(tempDir.resolve("zolt.lock"));
            assertTrue(lock.contains(
                    "id = \"org.junit.platform:junit-platform-console\"\nversion = \"1.11.4\""), () -> lock);
        }
    }

    @Test
    void managedConsoleAtSkewedLineFailsActionably() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("org.junit", "junit-bom", "5.10.2", """
                    <project>
                      <groupId>org.junit</groupId>
                      <artifactId>junit-bom</artifactId>
                      <version>5.10.2</version>
                      <packaging>pom</packaging>
                      <dependencyManagement>
                        <dependencies>
                          <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.10.2</version>
                          </dependency>
                          <dependency>
                            <groupId>org.junit.platform</groupId>
                            <artifactId>junit-platform-console</artifactId>
                            <version>1.11.4</version>
                          </dependency>
                        </dependencies>
                      </dependencyManagement>
                    </project>
                    """);
            addJupiterGraph(repository, "5.10.2", "1.10.2");
            addConsole(repository, "1.11.4");
            Files.writeString(tempDir.resolve("zolt.toml"), memberConfig("managed-console-skew") + """

                    [repositories]
                    central = false

                    [repositories.test]
                    url = "%s"

                    [platforms]
                    "org.junit:junit-bom" = "5.10.2"

                    [dependencies.test]
                    "org.junit.jupiter:junit-jupiter" = { managed = true }
                    """.formatted(repository.baseUri()));

            CommandResult result = execute(
                    "resolve",
                    "--cwd", tempDir.toString(),
                    "--cache-root", tempDir.resolve("cache").toString());

            assertEquals(1, result.exitCode());
            assertTrue(result.stderr().contains("Unaligned JUnit Platform"), () -> result.stderr());
            assertTrue(result.stderr().contains("junit-platform-console"), () -> result.stderr());
            assertTrue(result.stderr().contains("1.11.4"), () -> result.stderr());
            assertTrue(result.stderr().contains("1.10.2"), () -> result.stderr());
        }
    }

    private static void addJupiterGraph(
            CliTestRepository repository,
            String jupiterVersion,
            String platformVersion) {
        repository.addArtifact("org.junit.jupiter", "junit-jupiter", jupiterVersion, """
                <project>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>%s</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.platform</groupId>
                      <artifactId>junit-platform-engine</artifactId>
                      <version>%s</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(jupiterVersion, platformVersion));
        repository.addArtifact("org.junit.platform", "junit-platform-engine", platformVersion, """
                <project>
                  <groupId>org.junit.platform</groupId>
                  <artifactId>junit-platform-engine</artifactId>
                  <version>%s</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.platform</groupId>
                      <artifactId>junit-platform-commons</artifactId>
                      <version>%s</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(platformVersion, platformVersion));
        repository.addArtifact("org.junit.platform", "junit-platform-commons", platformVersion, """
                <project>
                  <groupId>org.junit.platform</groupId>
                  <artifactId>junit-platform-commons</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(platformVersion));
    }

    private static void addConsole(CliTestRepository repository, String version) {
        repository.addArtifact("org.junit.platform", "junit-platform-console", version, """
                <project>
                  <groupId>org.junit.platform</groupId>
                  <artifactId>junit-platform-console</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(version));
    }
}
