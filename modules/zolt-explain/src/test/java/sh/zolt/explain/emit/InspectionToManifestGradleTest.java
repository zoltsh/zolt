package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.explain.gradle.GradleInspectionResult;
import sh.zolt.explain.gradle.GradleStaticProjectInspector;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class InspectionToProjectConfigGradleTest {
    @TempDir
    private Path tempDir;

    private final InspectionToProjectConfig mapper = new InspectionToProjectConfig();

    @Test
    void gradleDraftUsesRootProjectNameGroupVersionAndMainClass() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'sales-report'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins { id 'java'\n    id 'application' }
                group = 'com.example'
                version = '0.3.1'
                application { mainClass = 'com.example.report.ReportApp' }
                dependencies {
                    implementation 'org.slf4j:slf4j-api:2.0.16'
                }
                """);

        GradleInspectionResult result = new GradleStaticProjectInspector().inspect(tempDir);
        DraftZoltToml draft = mapper.fromGradle(result);
        ProjectConfig config = draft.config();

        assertEquals("sales-report", config.project().name());
        assertEquals("com.example", config.project().group());
        assertEquals("0.3.1", config.project().version());
        assertEquals("com.example.report.ReportApp", config.project().main().orElseThrow());
        assertEquals("2.0.16", config.dependencies().get("org.slf4j:slf4j-api"));
        assertFalse(
                draft.notes().stream().anyMatch(note -> note.contains("could not read")),
                () -> "no cannot-read note expected when group/version are present: " + draft.notes());
    }

    @Test
    void gradleDraftFallsBackAndCommentsWhenGroupVersionAbsent() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'bare'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins { id 'java' }
                dependencies {
                    implementation 'com.example:lib:1.0'
                }
                """);

        GradleInspectionResult result = new GradleStaticProjectInspector().inspect(tempDir);
        DraftZoltToml draft = mapper.fromGradle(result);
        ProjectConfig config = draft.config();

        assertEquals("bare", config.project().name());
        assertEquals("com.example", config.project().group());
        assertEquals("0.1.0", config.project().version());
        assertTrue(config.project().main().isEmpty());
        assertTrue(
                draft.notes().stream().anyMatch(note ->
                        note.contains("group and version are placeholders")
                                && note.contains("could not read them")),
                () -> "expected the cannot-read fallback note: " + draft.notes());
    }

    @Test
    void gradleDraftUsesGradlePropertiesCoordinatesWithoutPlaceholderNotes() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'properties-demo'\n");
        Files.writeString(tempDir.resolve("gradle.properties"), """
                group=com.acme
                version=1.2.3
                """);
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins { id 'java' }
                dependencies {
                    implementation 'com.example:lib:1.0'
                }
                """);

        GradleInspectionResult result = new GradleStaticProjectInspector().inspect(tempDir);
        DraftZoltToml draft = mapper.fromGradle(result);
        ProjectConfig config = draft.config();

        assertEquals("com.acme", config.project().group());
        assertEquals("1.2.3", config.project().version());
        assertFalse(
                draft.notes().stream().anyMatch(note -> note.contains("placeholder")),
                () -> "gradle.properties coordinates should avoid placeholder notes: " + draft.notes());
    }

    @Test
    void gradleDraftInterpolatesDependencyVersionsFromExtAndGradleProperties() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'interpolated'\n");
        Files.writeString(tempDir.resolve("gradle.properties"), """
                group=com.acme
                version=1.2.3
                gsonVersion=2.11.0
                """);
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins { id 'java' }
                ext {
                    slf4jVersion = '2.0.13'
                    junitVersion = '5.10.2'
                }
                dependencies {
                    implementation "org.slf4j:slf4j-api:$slf4jVersion"
                    implementation "com.google.code.gson:gson:${gsonVersion}"
                    testImplementation "org.junit.jupiter:junit-jupiter:$junitVersion"
                }
                """);

        GradleInspectionResult result = new GradleStaticProjectInspector().inspect(tempDir);
        DraftZoltToml draft = mapper.fromGradle(result);
        ProjectConfig config = draft.config();

        assertEquals("2.0.13", config.dependencies().get("org.slf4j:slf4j-api"));
        assertEquals("2.11.0", config.dependencies().get("com.google.code.gson:gson"));
        assertEquals("5.10.2", config.testDependencies().get("org.junit.jupiter:junit-jupiter"));
        assertFalse(config.dependencies().values().stream().anyMatch(version -> version.contains("$")));
        assertFalse(config.testDependencies().values().stream().anyMatch(version -> version.contains("$")));
    }

    @Test
    void gradleDraftKeepsRichVersionCatalogDependencies() throws IOException {
        Files.createDirectories(tempDir.resolve("gradle"));
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'catalog-rich'\n");
        Files.writeString(tempDir.resolve("gradle/libs.versions.toml"), """
                [versions]
                junit4 = { require = "[4.12,)", prefer = "4.13.2" }

                [libraries]
                guava = { module = "com.google.guava:guava", version = { strictly = "[33.0, 34[", prefer = "33.4.8-jre" } }
                junit4 = { module = "junit:junit", version.ref = "junit4" }
                """);
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins { id 'java' }
                group = 'com.acme'
                version = '1.2.3'
                dependencies {
                    implementation libs.guava
                    testImplementation libs.junit4
                }
                """);

        GradleInspectionResult result = new GradleStaticProjectInspector().inspect(tempDir);
        DraftZoltToml draft = mapper.fromGradle(result);
        ProjectConfig config = draft.config();

        assertEquals("33.4.8-jre", config.dependencies().get("com.google.guava:guava"));
        assertEquals("4.13.2", config.testDependencies().get("junit:junit"));
        assertFalse(
                draft.notes().stream().anyMatch(note -> note.contains("no version")),
                () -> "rich catalog aliases should emit concrete versions: " + draft.notes());
    }

    //  -------------------------------------------------------------------------------

    @Test
    void gradleApiDependenciesRouteToApiChannelNotPlainDependencies() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle.kts"), "rootProject.name = \"lib\"\n");
        Files.writeString(tempDir.resolve("build.gradle.kts"), """
                plugins { `java-library` }
                group = "com.example"
                version = "1.0.0"
                dependencies {
                    api("com.google.guava:guava:33.4.8-jre")
                    implementation("org.slf4j:slf4j-api:2.0.16")
                }
                """);

        GradleInspectionResult result = new GradleStaticProjectInspector().inspect(tempDir);
        DraftZoltToml draft = mapper.fromGradle(result);
        ProjectConfig config = draft.config();

        assertEquals("33.4.8-jre", config.apiDependencies().get("com.google.guava:guava"),
                () -> "api dep must land in [api.dependencies]: " + config.apiDependencies());
        assertFalse(config.dependencies().containsKey("com.google.guava:guava"),
                () -> "api dep must not collapse into [dependencies]: " + config.dependencies());
        assertEquals("2.0.16", config.dependencies().get("org.slf4j:slf4j-api"));
        assertFalse(config.apiDependencies().containsKey("org.slf4j:slf4j-api"));
    }

    @Test
    void gradleBundleReferenceExpandsAcrossApiChannelInDraft() throws IOException {
        Files.createDirectories(tempDir.resolve("gradle"));
        Files.writeString(tempDir.resolve("gradle/libs.versions.toml"), """
                [versions]
                jackson = "2.17.1"

                [libraries]
                jackson-core = { module = "com.fasterxml.jackson.core:jackson-core", version.ref = "jackson" }
                jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }

                [bundles]
                jackson = ["jackson-core", "jackson-databind"]
                """);
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'lib'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins { id 'java-library' }
                group = 'com.example'
                version = '1.0.0'
                dependencies {
                    api libs.bundles.jackson
                }
                """);

        GradleInspectionResult result = new GradleStaticProjectInspector().inspect(tempDir);
        ProjectConfig config = mapper.fromGradle(result).config();

        assertEquals("2.17.1", config.apiDependencies().get("com.fasterxml.jackson.core:jackson-core"));
        assertEquals("2.17.1", config.apiDependencies().get("com.fasterxml.jackson.core:jackson-databind"));
    }

    @Test
    void gradleDraftCarriesSpockGroovyTestSources() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.createDirectories(tempDir.resolve("src/test/groovy/com/example"));
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'spock-gradle'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'groovy'
                }
                group = 'com.example'
                version = '1.0.0'

                dependencies {
                    testImplementation 'org.apache.groovy:groovy:4.0.22'
                    testImplementation 'org.spockframework:spock-core:2.3-groovy-4.0'
                    testImplementation 'org.junit.platform:junit-platform-console-standalone:1.11.4'
                }
                """);

        GradleInspectionResult result = new GradleStaticProjectInspector().inspect(tempDir);
        DraftZoltToml draft = mapper.fromGradle(result);
        ProjectConfig config = draft.config();

        assertEquals(List.of("src/test/groovy"), config.build().groovyTestSources());
        assertEquals("4.0.22", config.testDependencies().get("org.apache.groovy:groovy"));
        assertEquals("2.3-groovy-4.0", config.testDependencies().get("org.spockframework:spock-core"));
        assertEquals("1.11.4", config.testDependencies().get("org.junit.platform:junit-platform-console-standalone"));
    }
}
