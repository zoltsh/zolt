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

final class MavenAnnotationProcessorInspectionTest {
    @TempDir
    private Path tempDir;

    private final MavenStaticProjectInspector inspector = new MavenStaticProjectInspector();

    @Test
    void capturesAnnotationProcessorPathsAndSurfacesReports() throws IOException {
        writePomWithProcessorVersion("${mapstruct.version}");

        MavenInspectionResult result = inspector.inspect(tempDir);
        MavenProjectInspection project = result.projects().getFirst();
        MavenAnnotationProcessorInspection processor = project.annotationProcessors().getFirst();
        MavenExplainFormatter formatter = new MavenExplainFormatter();
        String text = formatter.text(result);
        String json = formatter.json(result);

        assertEquals("org.mapstruct:mapstruct-processor:1.6.3", processor.coordinate());
        assertEquals("1.6.3", processor.version());
        assertTrue(text.contains("annotation processors: 1"), () -> text);
        assertTrue(text.contains("- org.mapstruct:mapstruct-processor:1.6.3"), () -> text);
        assertTrue(text.contains("maven-compiler-plugin"), () -> text);
        assertTrue(json.contains("\"annotationProcessors\""), () -> json);
        assertTrue(json.contains("\"coordinate\": \"org.mapstruct:mapstruct-processor:1.6.3\""), () -> json);
        assertTrue(result.signals().stream().anyMatch(signal ->
                signal.id().equals("maven.annotation-processor.path")
                        && signal.severity() == ExplainSignal.Severity.WARN
                        && signal.category() == ExplainSignal.Category.BUILDABILITY
                        && signal.message().contains("org.mapstruct:mapstruct-processor:1.6.3")));
    }

    @Test
    void annotationProcessorSignalReachesScorecardAndBlockers() throws IOException {
        writePomWithProcessorVersion("${mapstruct.version}");

        MavenInspectionResult result = inspector.inspect(tempDir);
        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(result);
        String scorecardText = new MigrationReadinessScorecardFormatter().text(scorecard);
        String blockerText = new MigrationBlockerReportFormatter()
                .text(MigrationBlockerReports.from(scorecard));
        String blockerJson = new MigrationBlockerReportFormatter()
                .json(MigrationBlockerReports.from(scorecard));

        assertTrue(scorecardText.contains("generated-sources: planned"), () -> scorecardText);
        assertTrue(scorecardText.contains("maven-compiler-plugin annotationProcessorPaths"), () -> scorecardText);
        assertTrue(scorecardText.contains("[dependencies.processor]"), () -> scorecardText);
        assertTrue(scorecardText.contains("signal: maven.annotation-processor.path"), () -> scorecardText);
        assertTrue(blockerText.contains("maven-compiler-plugin annotationProcessorPaths"), () -> blockerText);
        assertTrue(blockerText.contains("signal: maven.annotation-processor.path"), () -> blockerText);
        assertTrue(blockerJson.contains("\"signalId\": \"maven.annotation-processor.path\""), () -> blockerJson);
        assertFalse(blockerText.contains("Status: ready"), () -> blockerText);
    }

    @Test
    void processorFreePomAddsNoAnnotationProcessorSignals() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                </project>
                """);

        MavenInspectionResult result = inspector.inspect(tempDir);

        assertTrue(result.projects().getFirst().annotationProcessors().isEmpty());
        assertTrue(result.signals().stream()
                .noneMatch(signal -> signal.id().equals("maven.annotation-processor.path")));
    }

    private void writePomWithProcessorVersion(String version) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <mapstruct.version>1.6.3</mapstruct.version>
                  </properties>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                          <annotationProcessorPaths>
                            <path>
                              <groupId>org.mapstruct</groupId>
                              <artifactId>mapstruct-processor</artifactId>
                              <version>%s</version>
                            </path>
                          </annotationProcessorPaths>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(version));
    }
}
