package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.explain.gradle.GradleStaticProjectInspector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies Gradle {@code platform(...)} / {@code enforcedPlatform(...)} consumption routes to
 * {@code [platforms]} (never a regular {@code [dependencies]} entry) and that version-less deps in a
 * build file with a platform emit as platform-managed {@code { managed = true }} rather than a hard
 * "add a version" review item. Mirrors {@link MavenDependencyManagementEmitTest}.
 */
final class GradlePlatformConsumptionEmitTest {
    @TempDir
    private Path tempDir;

    private final InspectionToManifest mapper = new InspectionToManifest();

    @Test
    void groovyPlatformRoutesToPlatformsAndManagesVersionlessDependency() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins { id 'java' }
                group = 'com.example'
                version = '1.0.0'
                dependencies {
                    implementation platform('com.fasterxml.jackson:jackson-bom:2.18.2')
                    implementation 'com.fasterxml.jackson.core:jackson-databind'
                }
                """);

        DraftZoltToml draft = draft();
        DraftManifestSubject subject = DraftManifestSubject.of(draft);

        assertEquals("2.18.2", subject.platforms().get("com.fasterxml.jackson:jackson-bom"),
                () -> "platform() import must land in [platforms]: " + subject.platforms());
        assertFalse(
                subject.coordinates(DependencyLane.IMPLEMENTATION)
                        .contains("com.fasterxml.jackson:jackson-bom"),
                () -> "the BOM pom must not land on the classpath as a dependency: "
                        + subject.coordinates(DependencyLane.IMPLEMENTATION));
        assertTrue(
                subject.managed(DependencyLane.IMPLEMENTATION)
                        .contains("com.fasterxml.jackson.core:jackson-databind"),
                () -> "version-less managed dep must render as { managed = true }: "
                        + subject.managed(DependencyLane.IMPLEMENTATION));
        assertFalse(
                subject.fixed(DependencyLane.IMPLEMENTATION)
                        .containsKey("com.fasterxml.jackson.core:jackson-databind"),
                () -> "platform-managed dep must not get a guessed version: "
                        + subject.fixed(DependencyLane.IMPLEMENTATION));
        assertTrue(draft.notes().stream().anyMatch(note ->
                        note.contains("com.fasterxml.jackson.core:jackson-databind")
                                && note.contains("verify a declared platform manages this coordinate")),
                () -> "version-less managed dep needs a soft review note: " + draft.notes());
        assertFalse(draft.notes().stream().anyMatch(note ->
                        note.contains("com.fasterxml.jackson.core:jackson-databind")
                                && note.contains("add one before resolving")),
                () -> "managed dep must not keep the hard add-a-version item: " + draft.notes());
    }

    @Test
    void kotlinPlatformRoutesToPlatforms() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"\n");
        Files.writeString(tempDir.resolve("build.gradle.kts"), """
                plugins { `java-library` }
                group = "com.example"
                version = "1.0.0"
                dependencies {
                    api(platform("com.fasterxml.jackson:jackson-bom:2.18.2"))
                    api("com.fasterxml.jackson.core:jackson-databind")
                }
                """);

        DraftManifestSubject subject = DraftManifestSubject.of(draft());

        assertEquals("2.18.2", subject.platforms().get("com.fasterxml.jackson:jackson-bom"),
                () -> "Kotlin-DSL platform() import must land in [platforms]: " + subject.platforms());
        assertFalse(subject.coordinates(DependencyLane.API).contains("com.fasterxml.jackson:jackson-bom"),
                () -> "the BOM pom must not land in [dependencies.api]: "
                        + subject.coordinates(DependencyLane.API));
        assertTrue(subject.managed(DependencyLane.API).contains("com.fasterxml.jackson.core:jackson-databind"),
                () -> "version-less api dep must render as { managed = true }: "
                        + subject.managed(DependencyLane.API));
    }

    @Test
    void catalogPlatformReferenceResolvesThroughVersionCatalog() throws IOException {
        Files.createDirectories(tempDir.resolve("gradle"));
        Files.writeString(tempDir.resolve("gradle/libs.versions.toml"), """
                [libraries]
                jackson-bom = { module = "com.fasterxml.jackson:jackson-bom", version = "2.18.2" }
                """);
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins { id 'java' }
                group = 'com.example'
                version = '1.0.0'
                dependencies {
                    implementation platform(libs.jackson.bom)
                }
                """);

        DraftManifestSubject subject = DraftManifestSubject.of(draft());

        assertEquals("2.18.2", subject.platforms().get("com.fasterxml.jackson:jackson-bom"),
                () -> "platform(libs.x) must resolve through the version catalog into [platforms]: "
                        + subject.platforms());
        assertFalse(
                subject.coordinates(DependencyLane.IMPLEMENTATION)
                        .contains("com.fasterxml.jackson:jackson-bom"),
                () -> "catalog platform import must not become a dependency: "
                        + subject.coordinates(DependencyLane.IMPLEMENTATION));
    }

    @Test
    void enforcedPlatformMapsToPlatformsWithStrictConstraintNote() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins { id 'java' }
                group = 'com.example'
                version = '1.0.0'
                dependencies {
                    implementation enforcedPlatform('com.fasterxml.jackson:jackson-bom:2.18.2')
                }
                """);

        DraftZoltToml draft = draft();
        DraftManifestSubject subject = DraftManifestSubject.of(draft);

        assertEquals("2.18.2", subject.platforms().get("com.fasterxml.jackson:jackson-bom"),
                () -> "enforcedPlatform() import must land in [platforms]: " + subject.platforms());
        assertTrue(draft.notes().stream().anyMatch(note ->
                        note.contains("enforcedPlatform")
                                && note.contains("[dependencies.constraints]")),
                () -> "enforcedPlatform needs a strict-constraint approximation note: " + draft.notes());
        assertTrue(subject.constraints().isEmpty(),
                () -> "enforcedPlatform must not auto-draft [dependencies.constraints]: "
                        + subject.constraints());
    }

    @Test
    void versionlessDependencyWithoutPlatformKeepsHardReviewItem() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins { id 'java' }
                group = 'com.example'
                version = '1.0.0'
                dependencies {
                    implementation 'com.example:needs-version'
                }
                """);

        DraftZoltToml draft = draft();
        DraftManifestSubject subject = DraftManifestSubject.of(draft);

        assertTrue(subject.managed(DependencyLane.IMPLEMENTATION).isEmpty(),
                () -> "no platform present: version-less dep must not be managed: "
                        + subject.managed(DependencyLane.IMPLEMENTATION));
        assertTrue(draft.notes().stream().anyMatch(note ->
                        note.contains("com.example:needs-version")
                                && note.contains("add one before resolving")),
                () -> "version-less dep without a platform stays a hard review item: " + draft.notes());
    }

    private DraftZoltToml draft() throws IOException {
        return mapper.fromGradle(new GradleStaticProjectInspector().inspect(tempDir));
    }
}
