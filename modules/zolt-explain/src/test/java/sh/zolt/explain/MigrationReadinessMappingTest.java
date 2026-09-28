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
    void gradleKotlinPluginMapsToUnsupportedCiFinding() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'kotlin-app'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'org.jetbrains.kotlin.jvm' version '1.9.24'
                }
                repositories { mavenCentral() }
                """);

        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(
                new GradleStaticProjectInspector().inspect(tempDir));
        MigrationReadinessFinding finding = finding(scorecard, "gradle.language.unsupported");
        String scorecardText = new MigrationReadinessScorecardFormatter().text(scorecard);
        String blockerText = new MigrationBlockerReportFormatter().text(MigrationBlockerReports.from(scorecard));

        assertEquals(MigrationReadinessCategory.UNSUPPORTED, finding.category());
        assertEquals("ci", concernFor(finding));
        assertEquals("concern:ci Gradle Kotlin or Scala plugin", finding.sourcePattern());
        assertEquals("supported Java or Groovy application modules", finding.zoltPrimitive());
        assertTrue(scorecardText.contains(
                "unsupported  Gradle Kotlin or Scala plugin -> supported Java or Groovy application modules"),
                () -> scorecardText);
        assertTrue(blockerText.contains(
                "unsupported  Gradle Kotlin or Scala plugin -> supported Java or Groovy application modules"),
                () -> blockerText);
        assertFalse(scorecardText.contains("gradle.language.unsupported -> explicit Zolt model"), () -> scorecardText);
        assertFalse(blockerText.contains("gradle.language.unsupported -> explicit Zolt model"), () -> blockerText);
    }

    @Test
    void mavenKotlinPluginMapsToUnsupportedCiFinding() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>kotlin-service</artifactId>
                  <version>1.0.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-maven-plugin</artifactId>
                        <version>1.9.24</version>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        MigrationReadinessScorecard scorecard = MigrationReadinessScorecards.from(
                new MavenStaticProjectInspector().inspect(tempDir));
        MigrationReadinessFinding finding = finding(scorecard, "maven.language.unsupported");
        String scorecardText = new MigrationReadinessScorecardFormatter().text(scorecard);
        String blockerText = new MigrationBlockerReportFormatter().text(MigrationBlockerReports.from(scorecard));

        assertEquals(MigrationReadinessCategory.UNSUPPORTED, finding.category());
        assertEquals("ci", concernFor(finding));
        assertEquals("concern:ci Maven unsupported language or Android plugin", finding.sourcePattern());
        assertEquals("normal Java application modules", finding.zoltPrimitive());
        assertTrue(scorecardText.contains(
                "unsupported  Maven unsupported language or Android plugin -> normal Java application modules"),
                () -> scorecardText);
        assertTrue(blockerText.contains(
                "unsupported  Maven unsupported language or Android plugin -> normal Java application modules"),
                () -> blockerText);
        assertFalse(scorecardText.contains("maven.language.unsupported -> explicit Zolt model"), () -> scorecardText);
        assertFalse(blockerText.contains("maven.language.unsupported -> explicit Zolt model"), () -> blockerText);
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
