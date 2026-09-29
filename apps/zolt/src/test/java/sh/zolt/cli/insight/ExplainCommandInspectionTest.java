package sh.zolt.cli.insight;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ExplainCommandInspectionTest {
    @TempDir
    private Path tempDir;

    @Test
    void explainMavenTextInspectsPomStatically() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                      <version>33.4.8-jre</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        CommandResult result = execute("explain", "--cwd", tempDir.toString(), "--source", "maven");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Zolt explain: Maven project"));
        assertTrue(result.stdout().contains("Projects: 1"));
        assertTrue(result.stdout().contains("demo, packaging=jar, java=21"));
        assertTrue(result.stdout().contains("dependencies: 1"));
        assertTrue(result.stdout().contains("did not execute Maven"));
        assertEquals("", result.stderr());
    }

    @Test
    void explainMavenJsonInspectsPomDeterministically() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>demo</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.11.4</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);

        CommandResult result = execute(
                "explain",
                "--cwd", tempDir.toString(),
                "--source", "maven",
                "--format", "json");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().startsWith("{\n  \"schemaVersion\": 1,"));
        assertTrue(result.stdout().contains("\"source\": \"maven\""));
        assertTrue(result.stdout().contains("\"root\": \"" + jsonPath(tempDir.toAbsolutePath().normalize()) + "\""));
        assertTrue(result.stdout().contains("\"coordinate\": \"org.junit.jupiter:junit-jupiter:5.11.4\""));
        assertEquals("", result.stderr());
    }

    @Test
    void explainMavenClassifiesUnsupportedBetaShapes() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>demo</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>dynamic-lib</artifactId>
                      <version>[1.0,2.0)</version>
                    </dependency>
                  </dependencies>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-maven-plugin</artifactId>
                        <version>2.2.0</version>
                      </plugin>
                      <plugin>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-maven-plugin</artifactId>
                        <version>4.0.0</version>
                        <executions>
                          <execution>
                            <goals>
                              <goal>process-aot</goal>
                            </goals>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                  <profiles>
                    <profile>
                      <id>ci</id>
                    </profile>
                  </profiles>
                </project>
                """);

        CommandResult result = execute(
                "explain",
                "--cwd", tempDir.toString(),
                "--source", "maven",
                "--format", "json");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\"id\": \"maven.dependency.dynamic-version\""));
        assertTrue(result.stdout().contains("\"id\": \"maven.kotlin.manual-migration\""));
        assertFalse(result.stdout().contains("\"id\": \"maven.language.unsupported\""));
        assertTrue(result.stdout().contains("\"id\": \"maven.framework-native.unsupported\""));
        assertTrue(result.stdout().contains("\"id\": \"maven.profile.detected\""));
        assertTrue(result.stdout().contains("\"status\": \"blocked\""));
        assertEquals("", result.stderr());
    }

    @Test
    void explainMavenClassifiesKotlinJvmAsManualReview() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>kotlin-app</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-maven-plugin</artifactId>
                        <version>2.2.0</version>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        CommandResult result = execute(
                "explain",
                "--cwd", tempDir.toString(),
                "--source", "maven",
                "--format", "json");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\"blockers\": 0"));
        assertTrue(result.stdout().contains("\"warnings\": 1"));
        assertTrue(result.stdout().contains("\"id\": \"maven.kotlin.manual-migration\""));
        assertTrue(result.stdout().contains("\"status\": \"manual-review\""));
        assertFalse(result.stdout().contains("\"id\": \"maven.language.unsupported\""));
        assertEquals("", result.stderr());
    }

    @Test
    void explainMavenReportsMalformedPomCleanly() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project><artifactId>broken</project>");

        CommandResult result = execute("explain", "--cwd", tempDir.toString(), "--source", "maven");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("error: Could not inspect Maven project."));
        assertTrue(result.stderr().contains("Fix malformed POM XML"));
    }

    private static String jsonPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
