package sh.zolt.explain.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.explain.ExplainSignal;
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

final class MavenPluginLifecycleClassificationTest {
    @TempDir
    private Path tempDir;

    private final MavenStaticProjectInspector inspector = new MavenStaticProjectInspector();

    @Test
    void defaultBoundKnownPluginsProduceStaticSignalsWithEffectivePhases() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>defaults</artifactId>
                  <version>1.0.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-failsafe-plugin</artifactId>
                        <executions>
                          <execution>
                            <goals>
                              <goal>integration-test</goal>
                              <goal>verify</goal>
                            </goals>
                          </execution>
                        </executions>
                      </plugin>
                      <plugin>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <executions>
                          <execution>
                            <goals>
                              <goal>test</goal>
                            </goals>
                          </execution>
                        </executions>
                      </plugin>
                      <plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <executions>
                          <execution>
                            <id>default-testCompile</id>
                            <goals>
                              <goal>testCompile</goal>
                            </goals>
                          </execution>
                        </executions>
                      </plugin>
                      <plugin>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-maven-plugin</artifactId>
                        <executions>
                          <execution>
                            <goals>
                              <goal>repackage</goal>
                            </goals>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        MavenInspectionResult result = inspector.inspect(tempDir);
        MavenProjectInspection project = result.projects().getFirst();

        assertPhases(project, "org.apache.maven.plugins:maven-failsafe-plugin", "integration-test", "verify");
        assertPhases(project, "org.apache.maven.plugins:maven-surefire-plugin", "test");
        assertPhases(project, "org.apache.maven.plugins:maven-compiler-plugin", "test-compile");
        assertPhases(project, "org.springframework.boot:spring-boot-maven-plugin", "package");
        assertTrue(result.signals().stream()
                .anyMatch(signal -> signal.id().equals("maven.plugin.static-signal")
                        && signal.message().contains("maven-failsafe-plugin")
                        && signal.message().contains("[integration-test, verify]")));
        assertTrue(result.signals().stream()
                .noneMatch(signal -> signal.id().equals("maven.plugin.lifecycle-binding")
                        && (signal.message().contains("maven-failsafe-plugin")
                                || signal.message().contains("maven-compiler-plugin"))));
    }

    @Test
    void defaultBoundUnknownCodegenPluginBecomesLifecycleBlocker() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>codegen</artifactId>
                  <version>1.0.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>com.helger.maven</groupId>
                        <artifactId>ph-javacc-maven-plugin</artifactId>
                        <version>4.1.4</version>
                        <executions>
                          <execution>
                            <goals>
                              <goal>javacc</goal>
                            </goals>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        MavenInspectionResult result = inspector.inspect(tempDir);
        assertPhases(result.projects().getFirst(), "com.helger.maven:ph-javacc-maven-plugin:4.1.4", "generate-sources");
        ExplainSignal signal = result.signals().stream()
                .filter(candidate -> candidate.id().equals("maven.plugin.lifecycle-binding"))
                .findFirst()
                .orElseThrow();
        assertEquals(ExplainSignal.Severity.BLOCK, signal.severity());
        assertTrue(signal.message().contains("effective lifecycle phase(s) [generate-sources]"));

        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(result);
        String blockers = new MigrationBlockerReportFormatter().text(MigrationBlockerReports.from(scorecard));
        assertTrue(new MigrationReadinessScorecardFormatter()
                .text(scorecard)
                .contains("generated-sources: blocked"));
        assertTrue(blockers.contains("Maven generated-source plugin -> [generated.main] and [generated.test]"), () -> blockers);
    }

    @Test
    void phaseNoneIsDisabledAndDuplicatePhasesAreDeduplicated() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>disabled</artifactId>
                  <version>1.0.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-jar-plugin</artifactId>
                        <executions>
                          <execution>
                            <id>jar-disabled</id>
                            <phase>none</phase>
                          </execution>
                          <execution>
                            <phase>verify</phase>
                          </execution>
                          <execution>
                            <phase>verify</phase>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        MavenInspectionResult result = inspector.inspect(tempDir);
        MavenPluginInspection plugin = result.projects().getFirst().plugins().getFirst();
        String text = new MavenExplainFormatter().text(result);
        String json = new MavenExplainFormatter().json(result);

        assertEquals("org.apache.maven.plugins:maven-jar-plugin", plugin.coordinate());
        assertEquals(java.util.List.of("verify"), plugin.phases());
        assertEquals(java.util.List.of("jar-disabled"), plugin.disabledExecutions());
        assertTrue(text.contains("disabled executions: jar-disabled"), () -> text);
        assertTrue(json.contains("\"disabledExecutions\": [\"jar-disabled\"]"), () -> json);
        assertFalse(result.signals().stream().anyMatch(signal -> signal.message().contains("none")), () -> result.signals().toString());
        assertTrue(result.signals().stream()
                .anyMatch(signal -> signal.message().contains("[verify]")));
        assertFalse(result.signals().stream()
                .anyMatch(signal -> signal.message().contains("[verify, verify]")));
    }

    @Test
    void pluginManagementOnlyEntriesAreCountedSeparatelyAndDoNotSignal() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>managed</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                  <build>
                    <pluginManagement>
                      <plugins>
                        <plugin>
                          <groupId>com.example</groupId>
                          <artifactId>managed-codegen-maven-plugin</artifactId>
                          <version>1.0.0</version>
                          <executions>
                            <execution>
                              <phase>generate-sources</phase>
                            </execution>
                          </executions>
                        </plugin>
                      </plugins>
                    </pluginManagement>
                  </build>
                </project>
                """);

        MavenInspectionResult result = inspector.inspect(tempDir);
        MavenPluginInspection plugin = result.projects().getFirst().plugins().getFirst();
        String text = new MavenExplainFormatter().text(result);

        assertTrue(plugin.pluginManagement());
        assertEquals(java.util.List.of("generate-sources"), plugin.phases());
        assertTrue(result.signals().isEmpty(), () -> "pluginManagement-only plugins should not signal: " + result.signals());
        assertTrue(text.contains("plugins: 0"), () -> text);
        assertTrue(text.contains("plugin management plugins: 1"), () -> text);
    }

    private static void assertPhases(MavenProjectInspection project, String coordinatePrefix, String... phases) {
        MavenPluginInspection plugin = project.plugins().stream()
                .filter(candidate -> candidate.coordinate().startsWith(coordinatePrefix))
                .findFirst()
                .orElseThrow();
        assertEquals(java.util.List.of(phases), plugin.phases(), plugin.coordinate());
    }
}
