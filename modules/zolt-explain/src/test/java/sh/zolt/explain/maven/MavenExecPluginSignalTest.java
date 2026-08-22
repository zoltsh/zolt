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

/** Verifies exec-shaped Maven plugins classify to {@code maven.plugin.exec-*} signals and route. */
final class MavenExecPluginSignalTest {
    @TempDir
    private Path tempDir;

    private final MavenStaticProjectInspector inspector = new MavenStaticProjectInspector();

    @Test
    void execMavenPluginJavaGoalBecomesMappableWarnRoutedToGeneratedSources() throws IOException {
        MavenInspectionResult result = inspect("""
                <plugin>
                  <groupId>org.codehaus.mojo</groupId>
                  <artifactId>exec-maven-plugin</artifactId>
                  <executions>
                    <execution>
                      <phase>generate-sources</phase>
                      <goals><goal>java</goal></goals>
                      <configuration><mainClass>com.example.Generator</mainClass></configuration>
                    </execution>
                  </executions>
                </plugin>
                """);
        ExplainSignal signal = signal(result, "maven.plugin.exec-mappable");
        assertEquals(ExplainSignal.Severity.WARN, signal.severity());
        assertTrue(signal.message().contains("com.example.Generator"), signal.message());
        assertFalse(
                result.signals().stream().anyMatch(candidate -> candidate.id().equals("maven.plugin.lifecycle-binding")),
                () -> "exec plugin should not also emit a lifecycle-binding block: " + result.signals());

        String text = scorecardText(result);
        assertTrue(text.contains("generated-sources: planned"), () -> text);
        assertTrue(text.contains("[generated.tools] exec step"), () -> text);
    }

    @Test
    void execMavenPluginExecGoalWithShellMetacharactersBecomesUnmappableBlock() throws IOException {
        MavenInspectionResult result = inspect("""
                <plugin>
                  <groupId>org.codehaus.mojo</groupId>
                  <artifactId>exec-maven-plugin</artifactId>
                  <executions>
                    <execution>
                      <phase>generate-resources</phase>
                      <goals><goal>exec</goal></goals>
                      <configuration>
                        <executable>sh</executable>
                        <arguments>
                          <argument>-c</argument>
                          <argument>clean &amp;&amp; build</argument>
                        </arguments>
                      </configuration>
                    </execution>
                  </executions>
                </plugin>
                """);
        ExplainSignal signal = signal(result, "maven.plugin.exec-unmappable");
        assertEquals(ExplainSignal.Severity.BLOCK, signal.severity());
        String blockers = blockersText(result);
        assertTrue(blockers.contains("[tasks.<id>] or CI"), () -> blockers);
    }

    @Test
    void frontendInstallNodeGoalDowngradesToProvisioningWarn() throws IOException {
        MavenInspectionResult result = inspect("""
                <plugin>
                  <groupId>com.github.eirslett</groupId>
                  <artifactId>frontend-maven-plugin</artifactId>
                  <executions>
                    <execution>
                      <goals><goal>install-node-and-npm</goal></goals>
                      <configuration><nodeVersion>v20.11.0</nodeVersion></configuration>
                    </execution>
                  </executions>
                </plugin>
                """);
        ExplainSignal signal = signal(result, "maven.plugin.exec-mappable");
        assertEquals(ExplainSignal.Severity.WARN, signal.severity());
        assertTrue(signal.message().contains("provisions Node"), signal.message());
        assertTrue(signal.nextStep().contains("asdf"), signal.nextStep());
    }

    private MavenInspectionResult inspect(String pluginXml) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <build><plugins>%s</plugins></build>
                </project>
                """.formatted(pluginXml));
        return inspector.inspect(tempDir);
    }

    private static ExplainSignal signal(MavenInspectionResult result, String id) {
        return result.signals().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing signal " + id + " in " + result.signals()));
    }

    private static String scorecardText(MavenInspectionResult result) {
        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(result);
        return new MigrationReadinessScorecardFormatter().text(scorecard);
    }

    private static String blockersText(MavenInspectionResult result) {
        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(result);
        return new MigrationBlockerReportFormatter().text(MigrationBlockerReports.from(scorecard));
    }
}
