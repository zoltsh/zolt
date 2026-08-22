package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.explain.gradle.GradleInspectionResult;
import sh.zolt.explain.gradle.GradleStaticProjectInspector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class InspectionToManifestGradleTest {
    @TempDir
    private Path tempDir;

    private final InspectionToManifest mapper = new InspectionToManifest();

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
        DraftManifestSubject subject = DraftManifestSubject.of(draft);

        assertEquals("sales-report", subject.name());
        assertEquals(Optional.of("com.example"), subject.group());
        assertEquals(Optional.of("0.3.1"), subject.version());
        assertEquals("com.example.report.ReportApp", subject.mainClass().orElseThrow());
        assertEquals("2.0.16", subject.fixed(DependencyLane.IMPLEMENTATION).get("org.slf4j:slf4j-api"));
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
        DraftManifestSubject subject = DraftManifestSubject.of(draft);

        assertEquals("bare", subject.name());
        assertEquals(Optional.of("com.example"), subject.group());
        assertEquals(Optional.of("0.1.0"), subject.version());
        assertTrue(subject.mainClass().isEmpty());
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
        DraftManifestSubject subject = DraftManifestSubject.of(draft);

        assertEquals(Optional.of("com.acme"), subject.group());
        assertEquals(Optional.of("1.2.3"), subject.version());
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
        DraftManifestSubject subject = DraftManifestSubject.of(draft);

        assertEquals("2.0.13", subject.fixed(DependencyLane.IMPLEMENTATION).get("org.slf4j:slf4j-api"));
        assertEquals("2.11.0", subject.fixed(DependencyLane.IMPLEMENTATION).get("com.google.code.gson:gson"));
        assertEquals("5.10.2", subject.fixed(DependencyLane.TEST).get("org.junit.jupiter:junit-jupiter"));
        assertFalse(subject.fixed(DependencyLane.IMPLEMENTATION).values().stream()
                .anyMatch(version -> version.contains("$")));
        assertFalse(subject.fixed(DependencyLane.TEST).values().stream()
                .anyMatch(version -> version.contains("$")));
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
        DraftManifestSubject subject = DraftManifestSubject.of(draft);

        assertEquals("33.4.8-jre", subject.fixed(DependencyLane.IMPLEMENTATION).get("com.google.guava:guava"));
        assertEquals("4.13.2", subject.fixed(DependencyLane.TEST).get("junit:junit"));
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
        DraftManifestSubject subject = DraftManifestSubject.of(draft);

        assertEquals("33.4.8-jre", subject.fixed(DependencyLane.API).get("com.google.guava:guava"),
                () -> "api dep must land in [dependencies.api]: " + subject.fixed(DependencyLane.API));
        assertFalse(subject.coordinates(DependencyLane.IMPLEMENTATION).contains("com.google.guava:guava"),
                () -> "api dep must not collapse into the implementation lane: "
                        + subject.coordinates(DependencyLane.IMPLEMENTATION));
        assertEquals("2.0.16", subject.fixed(DependencyLane.IMPLEMENTATION).get("org.slf4j:slf4j-api"));
        assertFalse(subject.coordinates(DependencyLane.API).contains("org.slf4j:slf4j-api"));
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
        DraftManifestSubject subject = DraftManifestSubject.of(mapper.fromGradle(result));

        assertEquals("2.17.1",
                subject.fixed(DependencyLane.API).get("com.fasterxml.jackson.core:jackson-core"));
        assertEquals("2.17.1",
                subject.fixed(DependencyLane.API).get("com.fasterxml.jackson.core:jackson-databind"));
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
        DraftManifestSubject subject = DraftManifestSubject.of(draft);

        // The final language has no groovy test-source list: Zolt derives the test root from the build
        // convention, so an audited src/test/groovy root is carried as review data instead.
        assertTrue(
                draft.notes().stream().anyMatch(note ->
                        note.contains("Test sources live outside") && note.contains("src/test/groovy")),
                () -> "expected the Groovy test root review note: " + draft.notes());
        assertEquals("4.0.22", subject.fixed(DependencyLane.TEST).get("org.apache.groovy:groovy"));
        assertEquals("2.3-groovy-4.0", subject.fixed(DependencyLane.TEST).get("org.spockframework:spock-core"));
        assertEquals("1.11.4",
                subject.fixed(DependencyLane.TEST).get("org.junit.platform:junit-platform-console-standalone"));
    }
}
