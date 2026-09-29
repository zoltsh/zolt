package sh.zolt.explain.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MavenStaticProjectInspectorRootsTest {
    @TempDir
    private Path tempDir;

    private final MavenStaticProjectInspector inspector = new MavenStaticProjectInspector();

    @Test
    void reportsConventionRootsOnlyWhenTheyExistOnDisk() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.createDirectories(tempDir.resolve("src/main/kotlin"));
        Files.createDirectories(tempDir.resolve("src/test/java"));
        Files.createDirectories(tempDir.resolve("src/test/kotlin"));
        Files.createDirectories(tempDir.resolve("src/main/resources"));
        Files.createDirectories(tempDir.resolve("src/test/resources"));
        Files.writeString(tempDir.resolve("pom.xml"), barePom("convention-roots"));

        MavenProjectInspection project = inspector.inspect(tempDir).projects().getFirst();

        assertEquals(List.of("src/main/java", "src/main/kotlin"), project.sourceRoots());
        assertEquals(List.of("src/test/java", "src/test/kotlin"), project.testSourceRoots());
        assertEquals(List.of("src/main/resources"), project.resourceRoots());
        assertEquals(List.of("src/test/resources"), project.testResourceRoots());
    }

    @Test
    void omitsConventionRootsThatDoNotExistOnDisk() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), barePom("bare"));

        MavenProjectInspection project = inspector.inspect(tempDir).projects().getFirst();

        assertEquals(List.of(), project.sourceRoots());
        assertEquals(List.of(), project.testSourceRoots());
        assertEquals(List.of(), project.resourceRoots());
        assertEquals(List.of(), project.testResourceRoots());
    }

    @Test
    void keepsDeclaredRootsAndBuildHelperSourcesEvenWhenAbsent() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>custom-roots</artifactId>
                  <version>1.0.0</version>
                  <build>
                    <sourceDirectory>src/java</sourceDirectory>
                    <testSourceDirectory>src/tests</testSourceDirectory>
                    <resources>
                      <resource>
                        <directory>config</directory>
                      </resource>
                    </resources>
                    <testResources>
                      <testResource>
                        <directory>test-config</directory>
                      </testResource>
                    </testResources>
                    <plugins>
                      <plugin>
                        <groupId>org.codehaus.mojo</groupId>
                        <artifactId>build-helper-maven-plugin</artifactId>
                        <executions>
                          <execution>
                            <goals>
                              <goal>add-source</goal>
                            </goals>
                            <configuration>
                              <sources>
                                <source>src/gen</source>
                              </sources>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        MavenProjectInspection project = inspector.inspect(tempDir).projects().getFirst();

        assertEquals(List.of("src/java", "src/gen"), project.sourceRoots());
        assertEquals(List.of("src/tests"), project.testSourceRoots());
        assertEquals(List.of("config"), project.resourceRoots());
        assertEquals(List.of("test-config"), project.testResourceRoots());
    }

    @Test
    void emptyDeclaredResourceBlocksDoNotFallBackToConventionRoots() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/resources"));
        Files.createDirectories(tempDir.resolve("src/test/resources"));
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>empty-resources</artifactId>
                  <version>1.0.0</version>
                  <build>
                    <resources/>
                    <testResources/>
                  </build>
                </project>
                """);

        MavenProjectInspection project = inspector.inspect(tempDir).projects().getFirst();

        assertEquals(List.of(), project.resourceRoots());
        assertEquals(List.of(), project.testResourceRoots());
    }

    @Test
    void keepsKotlinConventionRootsVisibleWhenPluginBehaviorIsUnsupported() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/kotlin"));
        Files.createDirectories(tempDir.resolve("src/test/kotlin"));
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>kapt-roots</artifactId>
                  <version>1.0.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-maven-plugin</artifactId>
                        <version>2.2.20</version>
                        <executions>
                          <execution>
                            <goals><goal>kapt</goal></goals>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        MavenInspectionResult result = inspector.inspect(tempDir);
        MavenProjectInspection project = result.projects().getFirst();

        assertEquals(List.of("src/main/kotlin"), project.sourceRoots());
        assertEquals(List.of("src/test/kotlin"), project.testSourceRoots());
        assertTrue(result.signals().stream()
                .anyMatch(signal -> signal.id().equals("maven.language.unsupported")));
    }

    private static String barePom(String artifactId) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                </project>
                """.formatted(artifactId);
    }
}
