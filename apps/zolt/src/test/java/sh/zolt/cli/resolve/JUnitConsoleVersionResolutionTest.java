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
            repository.addArtifact("org.junit.jupiter", "junit-jupiter", "5.11.4", """
                    <project>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.11.4</version>
                    </project>
                    """);
            repository.addArtifact("org.junit.platform", "junit-platform-console", "1.11.4", """
                    <project>
                      <groupId>org.junit.platform</groupId>
                      <artifactId>junit-platform-console</artifactId>
                      <version>1.11.4</version>
                    </project>
                    """);
            Files.writeString(tempDir.resolve("zolt.toml"), memberConfig("junit-console-version") + """

                    [repositories]
                    test = "%s"

                    [platforms]
                    "example:junit-platform" = "1.0"

                    [test.dependencies]
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
}
