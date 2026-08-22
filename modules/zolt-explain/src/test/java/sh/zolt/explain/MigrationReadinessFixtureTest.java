package sh.zolt.explain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.explain.gradle.GradleInspectionResult;
import sh.zolt.explain.gradle.GradleStaticProjectInspector;
import sh.zolt.explain.maven.MavenInspectionResult;
import sh.zolt.explain.maven.MavenStaticProjectInspector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MigrationReadinessFixtureTest {
    private static final Path FIXTURE_ROOT = MigrationExplainTestPaths.fixtureRoot();

    @TempDir
    Path tempDir;

    @Test
    void gradleEnterpriseSpringFixtureReportsReadinessScorecardWithoutExecutingGradle() throws IOException {
        Path fixture = fixture("gradle-enterprise-spring");
        Path marker = fixture.resolve("executed.txt");
        Files.deleteIfExists(marker);

        GradleInspectionResult first = new GradleStaticProjectInspector().inspect(fixture);
        GradleInspectionResult second = new GradleStaticProjectInspector().inspect(fixture);
        MigrationReadinessScorecardFormatter formatter = new MigrationReadinessScorecardFormatter();
        String text = normalize(formatter.text(MigrationReadinessScorecards.from(first)), fixture);
        String json = normalize(formatter.json(MigrationReadinessScorecards.from(first)), fixture);

        assertEquals(text, normalize(formatter.text(MigrationReadinessScorecards.from(second)), fixture));
        assertEquals(json, normalize(formatter.json(MigrationReadinessScorecards.from(second)), fixture));
        assertFalse(Files.exists(marker));
        assertTrue(text.contains("Zolt migration readiness scorecard: gradle project"));
        assertTrue(text.contains("repositories: non-deterministic"));
        assertTrue(text.contains("dependencies: blocked"));
        assertTrue(text.contains("generated-sources: supported"));
        assertTrue(text.contains("resources: supported"));
        assertTrue(text.contains("tests: supported"));
        assertTrue(text.contains("coverage: supported"));
        assertTrue(text.contains("package: blocked"));
        assertTrue(text.contains("publish: planned"));
        assertTrue(text.contains("ci: non-deterministic"));
        assertTrue(text.contains("environment variable read in Gradle build logic -> explicit Zolt project, runtime, or CI settings"));
        assertTrue(text.contains("mavenLocal() property switch -> local repository overlays"));
        assertTrue(text.contains("bootWar archive mutation -> package placement policy"));
        assertTrue(text.contains("This scorecard inspected build metadata statically and did not execute Maven or Gradle."));
        assertTrue(json.contains("\"command\": \"explain-scorecard\""));
        assertTrue(json.contains("\"name\": \"repositories\""));
        assertTrue(json.contains("\"category\": \"non-deterministic\""));
        assertTrue(json.contains("\"sourcePattern\": \"OpenAPI GenerateTask wired into sourceSets\""));
        assertTrue(json.contains("\"zoltPrimitive\": \"kind = \\\"openapi\\\" generated-source steps\""));
        assertTrue(json.contains("\"followUp\": \"\""));
    }

    @Test
    void gradleMissingBuildFileDegradesUnevidencedConcernsToUnknown() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), """
                rootProject.name = 'missing-build-file'
                include 'app'
                """);
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }\n");
        Files.createDirectories(tempDir.resolve("app"));

        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(
                new GradleStaticProjectInspector().inspect(tempDir));
        MigrationReadinessScorecardFormatter formatter = new MigrationReadinessScorecardFormatter();
        String text = formatter.text(scorecard);
        String json = formatter.json(scorecard);

        assertEquals("blocked", scorecard.status());
        assertEquals("blocked", concern(scorecard, "dependencies").status());
        assertEquals("unknown", concern(scorecard, "tests").status());
        assertEquals("unknown", concern(scorecard, "publish").status());
        assertEquals("unknown", concern(scorecard, "coverage").status());
        assertFalse(text.contains("tests: supported"), () -> text);
        assertTrue(text.contains("tests: unknown"), () -> text);
        assertTrue(text.contains("unknown  unread Gradle build logic -> explicit Zolt model for inspected build behavior"), () -> text);
        assertTrue(json.contains("\"status\": \"unknown\""), () -> json);
        assertTrue(json.contains("\"category\": \"unknown\""), () -> json);
        assertTrue(json.contains("\"sourcePattern\": \"unread Gradle build logic\""), () -> json);
        assertTrue(json.contains("\"message\": \"This concern could not be inspected because some Gradle build logic was unread by the static audit.\""), () -> json);
        assertTrue(json.contains("\"nextStep\": \"Review the unread Gradle build logic, then model this concern explicitly in zolt.toml before relying on the scorecard.\""), () -> json);
    }

    @Test
    void gradleConventionPluginDegradesUnevidencedConcernsButKeepsConventionFinding() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'conventions'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'com.example.java-conventions'
                }
                """);

        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(
                new GradleStaticProjectInspector().inspect(tempDir));
        String text = new MigrationReadinessScorecardFormatter().text(scorecard);

        assertEquals("blocked", scorecard.status());
        assertEquals("blocked", concern(scorecard, "ci").status());
        assertEquals("unknown", concern(scorecard, "dependencies").status());
        assertEquals("unknown", concern(scorecard, "package").status());
        assertTrue(text.contains("ci: blocked"), () -> text);
        assertTrue(text.contains("convention plugin -> explicit Zolt project model"), () -> text);
        assertFalse(text.contains("package: supported"), () -> text);
    }

    @Test
    void gradleApplyFromScriptPluginDegradesUnevidencedConcerns() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'script-plugin'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                }

                apply from: 'gradle/publishing.gradle'
                """);

        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(
                new GradleStaticProjectInspector().inspect(tempDir));
        String text = new MigrationReadinessScorecardFormatter().text(scorecard);
        String json = new MigrationReadinessScorecardFormatter().json(scorecard);

        assertEquals("blocked", scorecard.status());
        assertEquals("blocked", concern(scorecard, "ci").status());
        assertEquals("unknown", concern(scorecard, "publish").status());
        assertEquals("unknown", concern(scorecard, "coverage").status());
        assertTrue(text.contains("apply from script plugin -> explicit Zolt project model"), () -> text);
        assertTrue(json.contains("\"signalId\": \"gradle.script-plugin.apply-from\""), () -> json);
        assertTrue(json.contains("\"category\": \"unknown\""), () -> json);
    }

    @Test
    void gradleEnvironmentDrivenLogicDoesNotReportSupportedReadiness() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'env-driven'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                }

                version = System.getenv('BUILD_TAG') ?: 'local'

                tasks.named('test', Test) {
                    environment 'CI', System.getenv('CI') ?: ''
                }
                """);

        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(
                new GradleStaticProjectInspector().inspect(tempDir));
        String text = new MigrationReadinessScorecardFormatter().text(scorecard);
        String json = new MigrationReadinessScorecardFormatter().json(scorecard);

        assertEquals("non-deterministic", scorecard.status());
        assertEquals("non-deterministic", concern(scorecard, "ci").status());
        assertTrue(text.contains("environment variable read in Gradle build logic"), () -> text);
        assertTrue(json.contains("\"signalId\": \"gradle.environment-variable.read\""), () -> json);
        assertFalse(text.contains("Status: supported"), () -> text);
    }

    @Test
    void gradleSimpleFixtureKeepsSupportedDefaultsWhenFullyParsed() {
        Path fixture = fixture("gradle-simple");

        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(
                new GradleStaticProjectInspector().inspect(fixture));

        assertEquals("supported", scorecard.status());
        assertTrue(scorecard.concerns().stream()
                .allMatch(concern -> concern.status().equals("supported")));
    }

    @Test
    void gradleEnterpriseSpringFixtureReportsBlockersWithFollowUpsWithoutExecutingGradle() throws IOException {
        Path fixture = fixture("gradle-enterprise-spring");
        Path marker = fixture.resolve("executed.txt");
        Files.deleteIfExists(marker);

        GradleInspectionResult first = new GradleStaticProjectInspector().inspect(fixture);
        GradleInspectionResult second = new GradleStaticProjectInspector().inspect(fixture);
        MigrationBlockerReportFormatter formatter = new MigrationBlockerReportFormatter();
        String text = normalize(formatter.text(MigrationBlockerReports.from(MigrationReadinessScorecards.from(first))), fixture);
        String json = normalize(formatter.json(MigrationBlockerReports.from(MigrationReadinessScorecards.from(first))), fixture);

        assertEquals(text, normalize(formatter.text(MigrationBlockerReports.from(MigrationReadinessScorecards.from(second))), fixture));
        assertEquals(json, normalize(formatter.json(MigrationBlockerReports.from(MigrationReadinessScorecards.from(second))), fixture));
        assertFalse(Files.exists(marker));
        assertTrue(text.contains("Zolt migration blocker report: gradle project"));
        assertTrue(text.contains("blocked  configurations.all, excludes, force, or resolutionStrategy -> [dependencies.policy] and [dependencies.constraints]"));
        assertTrue(text.contains("blocked  imperative dependency or configuration mutation -> [dependencies], classpath lanes, processors, and generated roots"));
        assertTrue(text.contains("blocked  bootWar archive mutation -> package placement policy"));
        assertTrue(text.contains("non-deterministic  credentials resolved from Gradle properties, env, or defaults -> [repositories] credential identities"));
        assertTrue(text.contains("This blocker report inspected build metadata statically and did not execute Maven or Gradle."));
        assertFalse(text.contains("ReadOnly"));
        assertFalse(text.contains("ARTIFACTORY_ACCESS_TOKEN"));
        assertTrue(json.contains("\"command\": \"explain-blockers\""));
        assertTrue(json.contains("\"status\": \"blocked\""));
        assertTrue(json.contains("\"severity\": \"block\""));
        assertTrue(json.contains("\"sourcePattern\": \"bootWar archive mutation\""));
        assertTrue(json.contains("\"zoltPrimitive\": \"package placement policy\""));
        assertTrue(json.contains("\"followUp\": \"\""));
        assertTrue(json.contains("\"signalId\": \"gradle.repository.credentials\""));
    }

    @Test
    void mavenFixtureReportsDeterministicReadinessScorecard() {
        Path fixture = fixture("maven-simple");
        MavenInspectionResult first = new MavenStaticProjectInspector().inspect(fixture);
        MavenInspectionResult second = new MavenStaticProjectInspector().inspect(fixture);
        MigrationReadinessScorecardFormatter formatter = new MigrationReadinessScorecardFormatter();
        String text = normalize(formatter.text(MigrationReadinessScorecards.from(first)), fixture);
        String json = normalize(formatter.json(MigrationReadinessScorecards.from(first)), fixture);

        assertEquals(text, normalize(formatter.text(MigrationReadinessScorecards.from(second)), fixture));
        assertEquals(json, normalize(formatter.json(MigrationReadinessScorecards.from(second)), fixture));
        assertTrue(text.contains("Zolt migration readiness scorecard: maven project"));
        assertTrue(text.contains("Status: supported"));
        assertTrue(text.contains("repositories: supported"));
        assertTrue(text.contains("publish: supported"));
        assertTrue(json.contains("\"source\": \"maven\""));
        assertTrue(json.contains("\"category\": \"supported\""));
        assertTrue(json.contains("\"sourcePattern\": \"dependencies and dependencyManagement\""));
    }

    @Test
    void mavenSnapshotParentAndRepositoryMakeRepositoryReadinessNonDeterministic() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example.parents</groupId>
                    <artifactId>enterprise-parent</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                  </parent>
                  <groupId>com.example</groupId>
                  <artifactId>service</artifactId>
                  <version>1.0.0</version>
                  <repositories>
                    <repository>
                      <id>snapshots</id>
                      <url>https://repo.example.test/snapshots</url>
                      <snapshots>
                        <enabled>true</enabled>
                      </snapshots>
                    </repository>
                  </repositories>
                </project>
                """);

        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(
                new MavenStaticProjectInspector().inspect(tempDir));
        String text = new MigrationReadinessScorecardFormatter().text(scorecard);
        String json = new MigrationReadinessScorecardFormatter().json(scorecard);

        assertEquals("non-deterministic", scorecard.status());
        assertEquals("non-deterministic", concern(scorecard, "repositories").status());
        assertTrue(text.contains("SNAPSHOT Maven parent"), () -> text);
        assertTrue(text.contains("snapshots-enabled Maven repository"), () -> text);
        assertTrue(json.contains("\"signalId\": \"maven.parent.snapshot\""), () -> json);
        assertTrue(json.contains("\"signalId\": \"maven.repository.snapshots-enabled\""), () -> json);
    }

    @Test
    void mavenMultiModuleReactorSurfacesReactorConcernInScorecardAndBlockers() {
        Path fixture = fixture("maven-multimodule");
        MavenInspectionResult result = new MavenStaticProjectInspector().inspect(fixture);
        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(result);

        String scorecardText = new MigrationReadinessScorecardFormatter().text(scorecard);
        assertTrue(scorecardText.contains("multi-module reactor (pom aggregator with <modules>)"));
        assertTrue(scorecardText.contains("[workspace] with one member draft per module"));
        assertTrue(scorecardText.contains("signal: maven.reactor.detected"));

        String blockerText = new MigrationBlockerReportFormatter()
                .text(MigrationBlockerReports.from(scorecard));
        assertTrue(blockerText.contains("multi-module reactor (pom aggregator with <modules>)"));
        assertTrue(blockerText.contains("[workspace] with one member draft per module"));
        assertTrue(blockerText.contains("signal: maven.reactor.detected"));
        assertTrue(blockerText.contains("Run zolt explain --emit-toml to generate the Zolt workspace"));

        String blockerJson = new MigrationBlockerReportFormatter()
                .json(MigrationBlockerReports.from(scorecard));
        assertTrue(blockerJson.contains("\"signalId\": \"maven.reactor.detected\""));
        assertTrue(blockerJson.contains("\"followUp\": \"\""));
    }

    @Test
    void mavenReactorDynamicDependencyFindingsAreDistinctInScorecardAndBlockers() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>acme-parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>module-a</module>
                    <module>module-b</module>
                  </modules>
                </project>
                """);
        writeSnapshotDependencyPom("module-a", "lib-one", "2.0-SNAPSHOT");
        writeSnapshotDependencyPom("module-b", "lib-two", "3.1-SNAPSHOT");

        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(
                new MavenStaticProjectInspector().inspect(tempDir));
        String scorecardText = new MigrationReadinessScorecardFormatter().text(scorecard);
        String blockerText = new MigrationBlockerReportFormatter().text(MigrationBlockerReports.from(scorecard));
        String scorecardJson = new MigrationReadinessScorecardFormatter().json(scorecard);
        String blockerJson = new MigrationBlockerReportFormatter().json(MigrationBlockerReports.from(scorecard));

        assertDistinctDynamicVersionLines(scorecardText);
        assertDistinctDynamicVersionLines(blockerText);
        assertTrue(scorecardText.contains("[project: module-a] - Dependency `com.acme:lib-one:2.0-SNAPSHOT`"),
                () -> scorecardText);
        assertTrue(scorecardText.contains("[project: module-b] - Dependency `com.acme:lib-two:3.1-SNAPSHOT`"),
                () -> scorecardText);
        assertTrue(blockerText.contains("[project: module-a] - Dependency `com.acme:lib-one:2.0-SNAPSHOT`"),
                () -> blockerText);
        assertTrue(blockerText.contains("[project: module-b] - Dependency `com.acme:lib-two:3.1-SNAPSHOT`"),
                () -> blockerText);
        assertTrue(scorecardJson.contains("\"project\": \"module-a\""), () -> scorecardJson);
        assertTrue(scorecardJson.contains("\"message\": \"Dependency `com.acme:lib-one:2.0-SNAPSHOT`"),
                () -> scorecardJson);
        assertTrue(blockerJson.contains("\"project\": \"module-b\""), () -> blockerJson);
        assertTrue(blockerJson.contains("\"message\": \"Dependency `com.acme:lib-two:3.1-SNAPSHOT`"),
                () -> blockerJson);
    }

    private static Path fixture(String name) {
        return FIXTURE_ROOT.resolve(name);
    }

    private static MigrationReadinessConcern concern(MigrationReadinessScorecard scorecard, String name) {
        return scorecard.concerns().stream()
                .filter(concern -> concern.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static String normalize(String value, Path fixture) {
        return value.replace(fixture.toString().replace('\\', '/'), "$ROOT");
    }

    private void writeSnapshotDependencyPom(String module, String artifactId, String version) throws IOException {
        Path moduleDirectory = tempDir.resolve(module);
        Files.createDirectories(moduleDirectory);
        Files.writeString(moduleDirectory.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.acme</groupId>
                    <artifactId>acme-parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>%s</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>com.acme</groupId>
                      <artifactId>%s</artifactId>
                      <version>%s</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(module, artifactId, version));
    }

    private static void assertDistinctDynamicVersionLines(String text) {
        List<String> dynamicVersionLines = text.lines()
                .filter(line -> line.contains("SNAPSHOT or Maven version range -> fixed versions and [platforms]"))
                .toList();
        assertEquals(2, dynamicVersionLines.size(), () -> text);
        assertEquals(2, Set.copyOf(dynamicVersionLines).size(), () -> dynamicVersionLines.toString());
    }
}
