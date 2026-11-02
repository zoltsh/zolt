package sh.zolt.explain.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.explain.MigrationReadinessConcern;
import sh.zolt.explain.MigrationReadinessScorecard;
import sh.zolt.explain.MigrationReadinessScorecards;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MavenTestJavaVersionInspectionTest {
    @TempDir
    private Path tempDir;

    private final MavenStaticProjectInspector inspector = new MavenStaticProjectInspector();

    @Test
    void reportsPropertyDrivenTestJavaVersionAndDivergenceSignal() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>test-level</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>8</maven.compiler.release>
                    <maven.compiler.testRelease>17</maven.compiler.testRelease>
                  </properties>
                </project>
                """);

        MavenInspectionResult result = inspector.inspect(tempDir);
        MavenProjectInspection project = result.projects().getFirst();
        String text = new MavenExplainFormatter().text(result);
        String json = new MavenExplainFormatter().json(result);
        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(result);

        assertEquals("8", project.javaVersion());
        assertEquals("17", project.testJavaVersion());
        assertEquals(MavenJavaVersionProvenance.RELEASE, project.testJavaVersionProvenance());
        assertTrue(text.contains(", java=8)"), () -> text);
        assertTrue(text.contains("test java: 17"), () -> text);
        assertTrue(json.contains("\"javaVersion\": \"8\""), () -> json);
        assertTrue(json.contains("\"testJavaVersion\": \"17\""), () -> json);
        assertTrue(result.signals().stream()
                .anyMatch(signal -> signal.id().equals("maven.test-java-version.divergent")
                        && signal.message().contains("test Java version `17` differs")));
        assertEquals("unknown", concern(scorecard, "tests").status());
    }

    @Test
    void readsPluginConfigurationTestReleaseWhenPropertiesAreAbsent() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>test-level</artifactId>
                  <version>1.0.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                          <release>11</release>
                          <testRelease>17</testRelease>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        MavenProjectInspection project = inspector.inspect(tempDir).projects().getFirst();

        assertEquals("11", project.javaVersion());
        assertEquals("17", project.testJavaVersion());
    }

    @Test
    void readsTestTargetAndTestSourceFallbacks() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>test-target-level</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>8</maven.compiler.release>
                    <maven.compiler.testTarget>11</maven.compiler.testTarget>
                  </properties>
                </project>
                """);

        MavenProjectInspection propertyProject = inspector.inspect(tempDir).projects().getFirst();

        assertEquals("8", propertyProject.javaVersion());
        assertEquals("11", propertyProject.testJavaVersion());
        assertEquals(MavenJavaVersionProvenance.SOURCE_TARGET, propertyProject.testJavaVersionProvenance());

        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>test-source-level</artifactId>
                  <version>1.0.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                          <target>8</target>
                          <testSource>11</testSource>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        MavenProjectInspection pluginProject = inspector.inspect(tempDir).projects().getFirst();

        assertEquals("8", pluginProject.javaVersion());
        assertEquals("11", pluginProject.testJavaVersion());
        assertEquals(MavenJavaVersionProvenance.SOURCE_TARGET, pluginProject.testJavaVersionProvenance());
    }

    @Test
    void noTestOverrideKeepsReportShapeAndSignalListUnchanged() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>same-level</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                </project>
                """);

        MavenInspectionResult result = inspector.inspect(tempDir);
        MavenProjectInspection project = result.projects().getFirst();
        String text = new MavenExplainFormatter().text(result);
        String json = new MavenExplainFormatter().json(result);

        assertEquals("21", project.javaVersion());
        assertTrue(project.testJavaVersion().isBlank());
        assertEquals(MavenJavaVersionProvenance.UNKNOWN, project.testJavaVersionProvenance());
        assertTrue(result.signals().isEmpty(), () -> result.signals().toString());
        assertFalse(text.contains("test java:"), () -> text);
        assertFalse(json.contains("testJavaVersion"), () -> json);
    }

    @Test
    void identicalTestOverrideKeepsReportShapeAndSignalListUnchanged() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>same-level</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <maven.compiler.testRelease>21</maven.compiler.testRelease>
                  </properties>
                </project>
                """);

        MavenInspectionResult result = inspector.inspect(tempDir);
        MavenProjectInspection project = result.projects().getFirst();
        String text = new MavenExplainFormatter().text(result);
        String json = new MavenExplainFormatter().json(result);

        assertEquals("21", project.javaVersion());
        assertTrue(project.testJavaVersion().isBlank());
        assertEquals(MavenJavaVersionProvenance.RELEASE, project.testJavaVersionProvenance());
        assertTrue(result.signals().isEmpty(), () -> result.signals().toString());
        assertFalse(text.contains("test java:"), () -> text);
        assertFalse(json.contains("testJavaVersion"), () -> json);
    }

    private static MigrationReadinessConcern concern(MigrationReadinessScorecard scorecard, String name) {
        return scorecard.concerns().stream()
                .filter(concern -> concern.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
