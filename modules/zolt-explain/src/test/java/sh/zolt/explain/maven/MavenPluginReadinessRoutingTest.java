package sh.zolt.explain.maven;

import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.explain.MigrationBlockerReportFormatter;
import sh.zolt.explain.MigrationBlockerReports;
import sh.zolt.explain.MigrationReadinessScorecard;
import sh.zolt.explain.MigrationReadinessScorecardFormatter;
import sh.zolt.explain.MigrationReadinessScorecards;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MavenPluginReadinessRoutingTest {
    @TempDir
    private Path tempDir;

    private final MavenStaticProjectInspector inspector = new MavenStaticProjectInspector();

    @Test
    void routesPluginFindingsToCoordinateConcerns() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>plugin-routing</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.jacoco</groupId>
                        <artifactId>jacoco-maven-plugin</artifactId>
                        <version>0.8.13</version>
                      </plugin>
                      <plugin>
                        <artifactId>maven-jar-plugin</artifactId>
                        <version>3.4.2</version>
                        <executions>
                          <execution>
                            <phase>package</phase>
                          </execution>
                        </executions>
                      </plugin>
                      <plugin>
                        <artifactId>maven-gpg-plugin</artifactId>
                        <version>3.2.8</version>
                      </plugin>
                      <plugin>
                        <groupId>com.example</groupId>
                        <artifactId>custom-maven-plugin</artifactId>
                        <version>1.0.0</version>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        MavenInspectionResult result = inspector.inspect(tempDir);
        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(result);
        String text = new MigrationReadinessScorecardFormatter().text(scorecard);
        String json = new MigrationReadinessScorecardFormatter().json(scorecard);
        String blockers = new MigrationBlockerReportFormatter().text(MigrationBlockerReports.from(scorecard));

        assertConcern(text, "coverage", "planned", "Maven coverage plugin", "zolt coverage");
        assertConcern(text, "package", "blocked", "Maven package plugin", "[package]");
        assertConcern(text, "publish", "planned", "Maven publish/signing plugin", "[publish] and zolt publish --dry-run");
        assertConcern(text, "ci", "planned", "Maven plugin declared for static migration review", "explicit Zolt command or project model");
        assertTrue(blockers.contains("Maven package plugin -> [package]"), () -> blockers);
        assertTrue(json.contains("\"name\": \"coverage\""), () -> json);
        assertTrue(json.contains("\"sourcePattern\": \"Maven coverage plugin\""), () -> json);
        assertTrue(json.contains("\"name\": \"package\""), () -> json);
        assertTrue(json.contains("\"sourcePattern\": \"Maven package plugin\""), () -> json);
        assertTrue(json.contains("\"name\": \"publish\""), () -> json);
        assertTrue(json.contains("\"sourcePattern\": \"Maven publish/signing plugin\""), () -> json);
        assertTrue(json.contains("\"name\": \"ci\""), () -> json);
        assertTrue(json.contains("\"sourcePattern\": \"Maven plugin declared for static migration review\""), () -> json);
    }

    @Test
    void routesGeneratedSourcesAndTestPluginsByCoordinate() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>plugin-routing</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>com.helger.maven</groupId>
                        <artifactId>ph-javacc-maven-plugin</artifactId>
                        <version>4.1.4</version>
                        <executions>
                          <execution>
                            <phase>generate-sources</phase>
                          </execution>
                        </executions>
                      </plugin>
                      <plugin>
                        <artifactId>maven-failsafe-plugin</artifactId>
                        <version>3.5.3</version>
                        <executions>
                          <execution>
                            <phase>verify</phase>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(inspector.inspect(tempDir));
        String text = new MigrationReadinessScorecardFormatter().text(scorecard);

        assertConcern(text, "generated-sources", "blocked", "Maven generated-source plugin", "[generated.main] and [generated.test]");
        assertConcern(text, "tests", "planned", "Maven test execution plugin", "[test] and integration-test settings");
    }

    private static void assertConcern(
            String text,
            String concern,
            String status,
            String sourcePattern,
            String primitive) {
        assertTrue(text.contains(concern + ": " + status), () -> text);
        assertTrue(text.contains(sourcePattern + " -> " + primitive), () -> text);
    }
}
