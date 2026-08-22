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

/** Verifies live-database codegen plugins block as nondeterministic while DDL-based ones stay mappable. */
final class MavenDatabaseBackedCodegenTest {
    @TempDir
    private Path tempDir;

    private final MavenStaticProjectInspector inspector = new MavenStaticProjectInspector();

    @Test
    void jooqWithJdbcUrlBecomesNondeterministicBlock() throws IOException {
        MavenInspectionResult result = inspect("""
                <plugin>
                  <groupId>org.jooq</groupId>
                  <artifactId>jooq-codegen-maven</artifactId>
                  <executions>
                    <execution>
                      <phase>generate-sources</phase>
                      <goals><goal>generate</goal></goals>
                    </execution>
                  </executions>
                  <configuration>
                    <jdbc>
                      <driver>org.postgresql.Driver</driver>
                      <url>jdbc:postgresql://localhost:5432/app</url>
                    </jdbc>
                  </configuration>
                </plugin>
                """);
        ExplainSignal signal = signal(result, "maven.plugin.exec-nondeterministic");
        assertEquals(ExplainSignal.Severity.BLOCK, signal.severity());
        assertEquals(ExplainSignal.Category.NON_DETERMINISM, signal.category());
        assertFalse(result.signals().stream().anyMatch(s -> s.id().equals("maven.plugin.lifecycle-binding")),
                () -> result.signals().toString());

        String scorecard = scorecardText(result);
        assertTrue(scorecard.contains("generated-sources: non-deterministic"), () -> scorecard);
        assertTrue(blockersText(result).contains("committed DDL or cache = \"none\""), () -> blockersText(result));
    }

    @Test
    void flywayBecomesNondeterministicBlock() throws IOException {
        MavenInspectionResult result = inspect("""
                <plugin>
                  <groupId>org.flywaydb</groupId>
                  <artifactId>flyway-maven-plugin</artifactId>
                  <executions>
                    <execution>
                      <phase>process-resources</phase>
                      <goals><goal>migrate</goal></goals>
                    </execution>
                  </executions>
                </plugin>
                """);
        assertEquals(ExplainSignal.Severity.BLOCK, signal(result, "maven.plugin.exec-nondeterministic").severity());
    }

    @Test
    void jooqFromCommittedDdlStaysGeneratedSourcesWithExecSurfaceNextStep() throws IOException {
        MavenInspectionResult result = inspect("""
                <plugin>
                  <groupId>org.jooq</groupId>
                  <artifactId>jooq-codegen-maven</artifactId>
                  <executions>
                    <execution>
                      <phase>generate-sources</phase>
                      <goals><goal>generate</goal></goals>
                    </execution>
                  </executions>
                  <configuration>
                    <generator>
                      <database>
                        <name>org.jooq.meta.extensions.ddl.DDLDatabase</name>
                      </database>
                    </generator>
                  </configuration>
                </plugin>
                """);
        assertFalse(result.signals().stream().anyMatch(s -> s.id().equals("maven.plugin.exec-nondeterministic")),
                () -> "committed-DDL jOOQ must not be flagged nondeterministic: " + result.signals());
        String blockers = blockersText(result);
        assertTrue(blockers.contains("[generated.tools]"), () -> blockers);
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
