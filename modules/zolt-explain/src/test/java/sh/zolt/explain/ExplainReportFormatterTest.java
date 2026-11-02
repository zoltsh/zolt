package sh.zolt.explain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.explain.gradle.GradleDependencyInspection;
import sh.zolt.explain.gradle.GradleExplainFormatter;
import sh.zolt.explain.gradle.GradleInspectionResult;
import sh.zolt.explain.gradle.GradleKotlinProjectEvidence;
import sh.zolt.explain.gradle.GradlePluginInspection;
import sh.zolt.explain.gradle.GradleProjectInspection;
import sh.zolt.explain.gradle.GradleRepositoryInspection;
import sh.zolt.explain.gradle.GradleVersionCatalogAlias;
import sh.zolt.explain.maven.MavenExplainFormatter;
import sh.zolt.explain.maven.MavenInspectionResult;
import sh.zolt.explain.maven.MavenProjectInspection;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ExplainReportFormatterTest {
    @Test
    void mavenTextReportGroupsSignalsAndNextSteps() {
        MavenInspectionResult result = new MavenInspectionResult(
                Path.of("/repo"),
                List.of(new MavenProjectInspection(
                        Path.of("."),
                        "legacy",
                        true,
                        "com.example",
                        "1.0.0",
                        "Legacy",
                        "war",
                        "21",
                        "",
                        sh.zolt.explain.maven.MavenJavaVersionProvenance.RELEASE,
                        sh.zolt.explain.maven.MavenJavaVersionProvenance.UNKNOWN,
                        "21",
                        "none",
                        "UTF-8",
                        List.of(
                                "maven.compiler.proc",
                                "maven.compiler.release",
                                "project.build.sourceEncoding"),
                        List.of("app"),
                        List.of("src/main/java"),
                        List.of("src/test/java"),
                        false,
                        false,
                        false,
                        false,
                        false,
                        List.of("src/main/resources"),
                        List.of("src/test/resources"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of())),
                ExplainSignals.sorted(List.of(
                        ExplainSignals.MAVEN_PACKAGING_UNSUPPORTED.signal(
                                ".",
                                "Packaging `war` needs an explicit Zolt packaging primitive."),
                        ExplainSignals.MAVEN_DEPENDENCY_DYNAMIC_VERSION.signal(
                                ".",
                                "Dependency `com.example:legacy-lib:[1.0,2.0)` uses dynamic version `[1.0,2.0)` (version-policy rule: version-range)."))));

        assertEquals("""
                Zolt explain: Maven project

                Project
                  Root: /repo
                  Projects: 1
                  Signals: 2

                Projects
                  - . (legacy, packaging=war, java=21)
                    name: Legacy
                    coordinates: com.example:legacy:1.0.0
                    modules: app
                    dependencies: 0
                    managed dependencies: 0
                    imported BOMs: 0
                    annotation processors: 0
                    plugins: 0
                    profiles: 0

                What Zolt can build
                  ok    no static buildability issues found in this first inspection pass

                What can cache
                  ok    no static cacheability issues found in this first inspection pass

                Non-determinism
                  block  Dependency `com.example:legacy-lib:[1.0,2.0)` uses dynamic version `[1.0,2.0)` (version-policy rule: version-range).

                Migration blockers
                  block  Packaging `war` needs an explicit Zolt packaging primitive.

                Next steps
                  1. Replace Maven ranges or SNAPSHOTs with fixed versions before migrating.
                  2. Map this packaging mode to Zolt package settings before migrating.

                This command inspected Maven metadata statically and did not execute Maven.
                """,
                new MavenExplainFormatter().text(result));
    }

    @Test
    void mavenJsonSummaryPartitionsMixedSeveritySignals() {
        MavenInspectionResult result = new MavenInspectionResult(
                Path.of("/repo"),
                List.of(minimalMavenProject()),
                ExplainSignals.sorted(List.of(
                        ExplainSignals.MAVEN_PACKAGING_UNSUPPORTED.signal(
                                ".",
                                "Packaging `war` needs an explicit Zolt packaging primitive."),
                        new ExplainSignal(
                                ExplainSignal.Severity.UNKNOWN,
                                ExplainSignal.Category.BUILDABILITY,
                                ".",
                                "maven.synthetic.unknown",
                                "Synthetic unknown signal for summary coverage.",
                                "Review the unknown Maven fact."),
                        new ExplainSignal(
                                ExplainSignal.Severity.OK,
                                ExplainSignal.Category.BUILDABILITY,
                                ".",
                                "maven.synthetic.ok",
                                "Synthetic ok signal for summary coverage.",
                                "No action needed."))));

        String json = new MavenExplainFormatter().json(result);

        assertSummaryValue(json, "signals", 3);
        assertSummaryValue(json, "blockers", 1);
        assertSummaryValue(json, "warnings", 0);
        assertSummaryValue(json, "unknown", 1);
        assertSummaryValue(json, "ok", 1);
    }

    @Test
    void gradleJsonReportIncludesMigrationContract() {
        GradleInspectionResult result = new GradleInspectionResult(
                Path.of("/repo"),
                "settings.gradle",
                List.of("app"),
                List.of(new GradleVersionCatalogAlias("guava", "com.google.guava:guava:33.4.8-jre")),
                List.of(new GradleProjectInspection(
                        Path.of("."),
                        "demo",
                        "build.gradle",
                        "groovy",
                        "21",
                        java.util.Optional.of("com.example"),
                        java.util.Optional.of("1.0.0"),
                        java.util.Optional.of("com.example.Main"),
                        List.of(new GradlePluginInspection("java", "")),
                        List.of(new GradleRepositoryInspection("mavenCentral", "https://repo.maven.apache.org/maven2")),
                        List.of(new GradleDependencyInspection(
                                "implementation",
                                "libs.guava",
                                "com.google.guava:guava:33.4.8-jre",
                                "guava")),
                        List.of("src/main/java"),
                        List.of("src/test/java"),
                        List.of(),
                        List.of(),
                        GradleKotlinProjectEvidence.none())),
                ExplainSignals.sorted(List.of(
                        ExplainSignals.GRADLE_CUSTOM_TASK_DETECTED.signal(
                                ".",
                                "Gradle build declares custom tasks."))));

        assertEquals("""
                {
                  "schemaVersion": 1,
                  "source": "gradle",
                  "root": "/repo",
                  "settingsFile": "settings.gradle",
                  "summary": {
                    "includedProjects": 1,
                    "projects": 1,
                    "versionCatalogAliases": 1,
                    "signals": 1,
                    "blockers": 0,
                    "warnings": 1,
                    "unknown": 0,
                    "ok": 0
                  },
                  "includedProjects": ["app"],
                  "versionCatalogAliases": [
                    {
                      "alias": "guava",
                      "coordinate": "com.google.guava:guava:33.4.8-jre"
                    }
                  ],
                  "projects": [
                    {
                      "path": ".",
                      "name": "demo",
                      "buildFile": "build.gradle",
                      "dsl": "groovy",
                      "javaVersion": "21",
                      "group": "com.example",
                      "version": "1.0.0",
                      "mainClass": "com.example.Main",
                      "plugins": [
                        {
                          "id": "java",
                          "version": ""
                        }
                      ],
                      "repositories": [
                        {
                          "kind": "mavenCentral",
                          "url": "https://repo.maven.apache.org/maven2"
                        }
                      ],
                      "dependencies": [
                        {
                          "configuration": "implementation",
                          "notation": "libs.guava",
                          "resolvedCoordinate": "com.google.guava:guava:33.4.8-jre",
                          "versionCatalogAlias": "guava"
                        }
                      ],
                      "sourceRoots": ["src/main/java"],
                      "testSourceRoots": ["src/test/java"],
                      "groovyTestSourceRoots": []
                    }
                  ],
                  "signals": [
                    {
                      "severity": "warn",
                      "category": "buildability",
                      "project": ".",
                      "id": "gradle.custom-task.detected",
                      "message": "Gradle build declares custom tasks.",
                      "nextStep": "Review whether custom tasks generate sources, resources, tests, package outputs, or runtime assets."
                    }
                  ],
                  "migration": {
                    "status": "manual-review",
                    "nextSteps": ["Review the static report, then create zolt.toml and run zolt resolve."]
                  }
                }
                """,
                new GradleExplainFormatter().json(result));
    }

    @Test
    void gradleTextReportPrintsCoordinatesAndMainClassWhenKnown() {
        GradleInspectionResult result = new GradleInspectionResult(
                Path.of("/repo"),
                "settings.gradle",
                List.of(),
                List.of(),
                List.of(new GradleProjectInspection(
                        Path.of("."),
                        "demo",
                        "build.gradle",
                        "groovy",
                        "21",
                        java.util.Optional.of("com.example"),
                        java.util.Optional.of("1.0.0"),
                        java.util.Optional.of("com.example.Main"),
                        List.of(new GradlePluginInspection("java", "")),
                        List.of(),
                        List.of(),
                        List.of("src/main/java"),
                        List.of("src/test/java"),
                        List.of(),
                        List.of(),
                        GradleKotlinProjectEvidence.none())),
                List.of());

        String text = new GradleExplainFormatter().text(result);

        assertTrue(text.contains("coordinates: com.example:demo:1.0.0"), () -> text);
        assertTrue(text.contains("main class: com.example.Main"), () -> text);
    }

    @Test
    void gradleJsonSummaryCountsOkSeveritySignals() {
        GradleInspectionResult result = new GradleInspectionResult(
                Path.of("/repo"),
                "settings.gradle",
                List.of(),
                List.of(),
                List.of(minimalGradleProject()),
                ExplainSignals.sorted(List.of(
                        ExplainSignals.GRADLE_ENTERPRISE_PLUGIN_MAPPED.signal(
                                ".",
                                "Gradle plugin `java` maps to Zolt Java source, javac, classpath, and package primitives."),
                        ExplainSignals.GRADLE_ENTERPRISE_PLUGIN_MAPPED.signal(
                                ".",
                                "Gradle plugin `jacoco` maps to Zolt coverage command."))));

        String json = new GradleExplainFormatter().json(result);

        assertSummaryValue(json, "signals", 2);
        assertSummaryValue(json, "blockers", 0);
        assertSummaryValue(json, "warnings", 0);
        assertSummaryValue(json, "unknown", 0);
        assertSummaryValue(json, "ok", 2);
    }

    private static MavenProjectInspection minimalMavenProject() {
        return new MavenProjectInspection(
                Path.of("."),
                "demo",
                true,
                "com.example",
                "1.0.0",
                "",
                "jar",
                "21",
                "",
                sh.zolt.explain.maven.MavenJavaVersionProvenance.RELEASE,
                sh.zolt.explain.maven.MavenJavaVersionProvenance.UNKNOWN,
                "21",
                "none",
                "UTF-8",
                List.of(
                        "maven.compiler.proc",
                        "maven.compiler.release",
                        "project.build.sourceEncoding"),
                List.of(),
                List.of("src/main/java"),
                List.of("src/test/java"),
                false,
                false,
                false,
                false,
                false,
                List.of("src/main/resources"),
                List.of("src/test/resources"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static GradleProjectInspection minimalGradleProject() {
        return new GradleProjectInspection(
                Path.of("."),
                "demo",
                "build.gradle",
                "groovy",
                "21",
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                List.of(
                        new GradlePluginInspection("java", ""),
                        new GradlePluginInspection("jacoco", "")),
                List.of(),
                List.of(),
                List.of("src/main/java"),
                List.of("src/test/java"),
                List.of(),
                List.of(),
                GradleKotlinProjectEvidence.none());
    }

    private static void assertSummaryValue(String json, String key, int value) {
        assertTrue(
                json.contains("\"" + key + "\": " + value),
                () -> "missing " + key + "=" + value + " in:\n" + json);
    }
}
