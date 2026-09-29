package sh.zolt.explain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.explain.gradle.GradleInspectionResult;
import sh.zolt.explain.gradle.GradleMigrationReadinessFindings;
import sh.zolt.explain.gradle.GradleStaticProjectInspector;
import sh.zolt.explain.maven.MavenInspectionResult;
import sh.zolt.explain.maven.MavenMigrationReadinessFindings;
import sh.zolt.explain.maven.MavenStaticProjectInspector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MigrationReadinessMappingTest {
    @TempDir
    private Path tempDir;

    @Test
    void gradleKotlinJvmPluginMapsToPlannedManualMigration() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'kotlin-app'\n");
        Files.writeString(tempDir.resolve("build.gradle.kts"), """
                plugins {
                    java
                    kotlin("jvm") version "2.2.0"
                }
                repositories { mavenCentral() }
                """);

        GradleInspectionResult inspection = new GradleStaticProjectInspector().inspect(tempDir);
        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(inspection);
        MigrationBlockerReport blockers = MigrationBlockerReports.from(scorecard);
        MigrationReadinessFinding finding = finding(scorecard, "gradle.kotlin.manual-migration");
        String scorecardText = new MigrationReadinessScorecardFormatter().text(scorecard);
        String blockerText = new MigrationBlockerReportFormatter().text(blockers);

        assertTrue(inspection.signals().stream().anyMatch(signal ->
                signal.id().equals("gradle.kotlin.manual-migration")
                        && signal.severity() == ExplainSignal.Severity.WARN));
        assertFalse(inspection.signals().stream().anyMatch(signal ->
                signal.id().equals("gradle.language.unsupported")));
        assertEquals("planned", scorecard.status());
        assertEquals("planned", blockers.status());
        assertEquals(MigrationReadinessCategory.PLANNED, finding.category());
        assertEquals("ci", concernFor(finding));
        assertEquals("concern:ci Gradle Kotlin/JVM plugin requiring manual migration", finding.sourcePattern());
        assertEquals("[toolchain.kotlin], Kotlin source roots, and [dependencies]", finding.zoltPrimitive());
        assertTrue(scorecardText.contains(
                "planned  Gradle Kotlin/JVM plugin requiring manual migration"
                        + " -> [toolchain.kotlin], Kotlin source roots, and [dependencies]"),
                () -> scorecardText);
        assertTrue(blockerText.contains(
                "planned  Gradle Kotlin/JVM plugin requiring manual migration"
                        + " -> [toolchain.kotlin], Kotlin source roots, and [dependencies]"),
                () -> blockerText);
    }

    @Test
    void mavenKotlinJvmPluginMapsToPlannedManualMigration() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>kotlin-service</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-maven-plugin</artifactId>
                        <version>1.9.24</version>
                        <executions>
                          <execution>
                            <goals>
                              <goal>compile</goal>
                              <goal>test-compile</goal>
                            </goals>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        MavenInspectionResult inspection = new MavenStaticProjectInspector().inspect(tempDir);
        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(inspection);
        MigrationBlockerReport blockers = MigrationBlockerReports.from(scorecard);
        MigrationReadinessFinding finding = finding(scorecard, "maven.kotlin.manual-migration");
        String scorecardText = new MigrationReadinessScorecardFormatter().text(scorecard);
        String blockerText = new MigrationBlockerReportFormatter().text(blockers);

        assertTrue(inspection.signals().stream().anyMatch(signal ->
                signal.id().equals("maven.kotlin.manual-migration")
                        && signal.severity() == ExplainSignal.Severity.WARN));
        assertFalse(inspection.signals().stream().anyMatch(signal ->
                signal.id().equals("maven.language.unsupported")));
        assertEquals("planned", scorecard.status());
        assertEquals("planned", blockers.status());
        assertEquals(MigrationReadinessCategory.PLANNED, finding.category());
        assertEquals("ci", concernFor(finding));
        assertEquals("concern:ci Maven Kotlin/JVM plugin requiring manual migration", finding.sourcePattern());
        assertEquals("[toolchain.kotlin], Kotlin source roots, and [dependencies]", finding.zoltPrimitive());
        assertTrue(scorecardText.contains(
                "planned  Maven Kotlin/JVM plugin requiring manual migration"
                        + " -> [toolchain.kotlin], Kotlin source roots, and [dependencies]"),
                () -> scorecardText);
        assertTrue(blockerText.contains(
                "planned  Maven Kotlin/JVM plugin requiring manual migration"
                        + " -> [toolchain.kotlin], Kotlin source roots, and [dependencies]"),
                () -> blockerText);
    }

    @Test
    void gradleMultiplatformPluginRemainsUnsupported() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle.kts"), "rootProject.name = \"multiplatform\"\n");
        Files.writeString(tempDir.resolve("build.gradle.kts"), """
                plugins {
                    kotlin("multiplatform") version "2.2.0"
                }
                """);

        GradleInspectionResult inspection = new GradleStaticProjectInspector().inspect(tempDir);
        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(inspection);

        assertFalse(inspection.signals().stream().anyMatch(signal ->
                signal.id().equals("gradle.kotlin.manual-migration")));
        assertTrue(inspection.signals().stream().anyMatch(signal ->
                signal.id().equals("gradle.language.unsupported")
                        && signal.severity() == ExplainSignal.Severity.BLOCK));
        assertEquals(MigrationReadinessCategory.UNSUPPORTED,
                finding(scorecard, "gradle.language.unsupported").category());
    }

    @Test
    void legacyGradleKotlinPluginMapsToManualMigration() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'legacy-kotlin'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id('kotlin')
                }
                """);

        GradleInspectionResult inspection = new GradleStaticProjectInspector().inspect(tempDir);

        assertTrue(inspection.signals().stream().anyMatch(signal ->
                signal.id().equals("gradle.kotlin.manual-migration")
                        && signal.severity() == ExplainSignal.Severity.WARN));
        assertFalse(inspection.signals().stream().anyMatch(signal ->
                signal.id().equals("gradle.language.unsupported")));
    }

    @Test
    void mavenKaptExecutionRemainsUnsupported() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>kapt-service</artifactId>
                  <version>1.0.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-maven-plugin</artifactId>
                        <version>2.2.0</version>
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

        MavenInspectionResult inspection = new MavenStaticProjectInspector().inspect(tempDir);
        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(inspection);

        assertFalse(inspection.signals().stream().anyMatch(signal ->
                signal.id().equals("maven.kotlin.manual-migration")));
        assertTrue(inspection.signals().stream().anyMatch(signal ->
                signal.id().equals("maven.language.unsupported")
                        && signal.severity() == ExplainSignal.Severity.BLOCK));
        assertEquals(MigrationReadinessCategory.UNSUPPORTED,
                finding(scorecard, "maven.language.unsupported").category());
    }

    @Test
    void gradleCatalogBundleUnresolvedMapsToNamedDependenciesFinding() throws IOException {
        Files.createDirectories(tempDir.resolve("gradle"));
        Files.writeString(tempDir.resolve("gradle/libs.versions.toml"), """
                [libraries]
                guava = { module = "com.google.guava:guava", version = "33.4.8-jre" }

                [bundles]
                core = ["guava", "missing-lib"]
                """);
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins { id 'java' }
                dependencies {
                    implementation libs.bundles.core
                }
                """);

        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(
                new GradleStaticProjectInspector().inspect(tempDir));
        MigrationReadinessFinding finding = finding(scorecard, "gradle.version-catalog.bundle-unresolved");
        String scorecardText = new MigrationReadinessScorecardFormatter().text(scorecard);
        String blockerText = new MigrationBlockerReportFormatter().text(MigrationBlockerReports.from(scorecard));

        assertEquals(MigrationReadinessCategory.BLOCKED, finding.category());
        assertEquals("dependencies", concernFor(finding));
        assertEquals("concern:dependencies unresolved Gradle version-catalog bundle", finding.sourcePattern());
        assertEquals("[dependencies] with explicit library aliases", finding.zoltPrimitive());
        assertTrue(scorecardText.contains(
                "blocked  unresolved Gradle version-catalog bundle -> [dependencies] with explicit library aliases"),
                () -> scorecardText);
        assertTrue(blockerText.contains(
                "blocked  unresolved Gradle version-catalog bundle -> [dependencies] with explicit library aliases"),
                () -> blockerText);
        assertFalse(scorecardText.contains("gradle.version-catalog.bundle-unresolved -> explicit Zolt model"),
                () -> scorecardText);
        assertFalse(blockerText.contains("gradle.version-catalog.bundle-unresolved -> explicit Zolt model"),
                () -> blockerText);
    }

    @Test
    void unsupportedAndroidAndFrameworkNativeSignalsMapAwayFromDependencies() {
        assertMapped(
                GradleMigrationReadinessFindings.map(ExplainSignals.GRADLE_ANDROID_UNSUPPORTED.signal(
                        ".", "Gradle plugin `com.android.application` declares an Android project.")),
                "package",
                "Gradle Android project",
                "normal Java application package modes");
        assertMapped(
                GradleMigrationReadinessFindings.map(ExplainSignals.GRADLE_FRAMEWORK_NATIVE_UNSUPPORTED.signal(
                        ".", "Gradle plugin `org.graalvm.buildtools.native` declares native/AOT behavior.")),
                "package",
                "Gradle framework-native or dev-mode behavior",
                "typed Zolt framework settings");
        assertMapped(
                MavenMigrationReadinessFindings.map(ExplainSignals.MAVEN_FRAMEWORK_NATIVE_UNSUPPORTED.signal(
                        ".", "Plugin `org.graalvm.buildtools:native-maven-plugin:0.10.2` declares native behavior.")),
                "package",
                "Maven framework-native plugin behavior",
                "typed Zolt framework settings");
    }

    @Test
    void unknownSignalIdsStillUseGenericFallback() {
        MigrationReadinessFinding gradle = GradleMigrationReadinessFindings.map(new ExplainSignal(
                ExplainSignal.Severity.BLOCK,
                ExplainSignal.Category.MIGRATION_BLOCKER,
                ".",
                "gradle.future.signal",
                "Future Gradle signal.",
                "Review the future signal."));
        MigrationReadinessFinding maven = MavenMigrationReadinessFindings.map(new ExplainSignal(
                ExplainSignal.Severity.BLOCK,
                ExplainSignal.Category.MIGRATION_BLOCKER,
                ".",
                "maven.future.signal",
                "Future Maven signal.",
                "Review the future signal."));

        assertEquals(MigrationReadinessCategory.BLOCKED, gradle.category());
        assertEquals("dependencies", concernFor(gradle));
        assertEquals("concern:dependencies gradle.future.signal", gradle.sourcePattern());
        assertEquals("explicit Zolt model", gradle.zoltPrimitive());
        assertEquals(MigrationReadinessCategory.BLOCKED, maven.category());
        assertEquals("dependencies", concernFor(maven));
        assertEquals("concern:dependencies maven.future.signal", maven.sourcePattern());
        assertEquals("explicit Zolt model", maven.zoltPrimitive());
    }

    private static MigrationReadinessFinding finding(MigrationReadinessScorecard scorecard, String signalId) {
        return scorecard.concerns().stream()
                .flatMap(concern -> concern.findings().stream())
                .filter(finding -> finding.signalId().equals(signalId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing signal " + signalId + " in " + scorecard));
    }

    private static void assertMapped(
            MigrationReadinessFinding finding,
            String concern,
            String sourcePattern,
            String zoltPrimitive) {
        assertEquals(MigrationReadinessCategory.UNSUPPORTED, finding.category());
        assertEquals(concern, concernFor(finding));
        assertEquals("concern:" + concern + " " + sourcePattern, finding.sourcePattern());
        assertEquals(zoltPrimitive, finding.zoltPrimitive());
        assertFalse(finding.sourcePattern().contains(finding.signalId()));
    }

    private static String concernFor(MigrationReadinessFinding finding) {
        int start = "concern:".length();
        int end = finding.sourcePattern().indexOf(' ');
        return finding.sourcePattern().substring(start, end);
    }
}
