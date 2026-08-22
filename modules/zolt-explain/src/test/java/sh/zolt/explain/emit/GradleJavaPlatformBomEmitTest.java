package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.explain.gradle.GradleStaticProjectInspector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies a Gradle {@code java-platform} project drafts a {@code [bom]} member: {@code platform(...)}
 * imports to {@code [bom.imports]}, {@code constraints { }} pins to {@code [bom.versions]}, no
 * dependency/build scaffolding. Mirrors {@link MavenBomEmitTest}.
 */
final class GradleJavaPlatformBomEmitTest {
    @TempDir
    private Path tempDir;

    private final InspectionToManifest mapper = new InspectionToManifest();

    @Test
    void groovyJavaPlatformDraftsBomMember() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'acme-bom'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'java-platform'
                }
                group = 'com.acme.platform'
                version = '1.0.0'
                javaPlatform {
                    allowDependencies()
                }
                dependencies {
                    api platform('com.fasterxml.jackson:jackson-bom:2.18.2')
                    api 'org.postgresql:postgresql:42.7.4'
                    constraints {
                        api 'com.example:lib-a:1.2.0'
                        runtime 'com.example:lib-b:3.4.5'
                    }
                }
                """);

        DraftZoltToml draft = draft();
        DraftManifestSubject subject = DraftManifestSubject.of(draft);

        assertTrue(subject.bom().isPresent(),
                () -> "a java-platform project must draft a [bom] member: " + subject.manifest());
        // The platform() import becomes a [bom.imports] entry.
        assertEquals("2.18.2", subject.bomImports().get("com.fasterxml.jackson:jackson-bom"),
                () -> "platform() import must become a [bom.imports] entry: " + subject.bomImports());
        // The constraints become [bom.versions] entries (api and runtime alike).
        assertEquals("1.2.0", subject.bomVersions().get("com.example:lib-a"),
                () -> "api constraint must become a [bom.versions] pin: " + subject.bomVersions());
        assertEquals("3.4.5", subject.bomVersions().get("com.example:lib-b"),
                () -> "runtime constraint must become a [bom.versions] pin: " + subject.bomVersions());
        // A BOM carries no dependencies and no source scaffolding.
        assertTrue(subject.manifest().dependencies().isEmpty(),
                () -> "a drafted BOM must carry no [dependencies]: " + subject.manifest().dependencies());
        assertTrue(subject.coordinates(DependencyLane.API).isEmpty(),
                () -> "api platform()/plain deps must not land in [dependencies.api]: "
                        + subject.coordinates(DependencyLane.API));
        // The allowDependencies() plain dep becomes a review note, not a dependency.
        assertTrue(draft.notes().stream().anyMatch(note ->
                        note.contains("org.postgresql:postgresql")
                                && note.contains("carries no dependencies")),
                () -> "plain dependency in a java-platform BOM needs a review note: " + draft.notes());
        assertTrue(draft.notes().stream().anyMatch(note -> note.contains("Drafted a [bom] member")),
                () -> "expected the drafted-bom review note: " + draft.notes());
    }

    @Test
    void kotlinJavaPlatformDraftsBomMember() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle.kts"), "rootProject.name = \"acme-bom\"\n");
        Files.writeString(tempDir.resolve("build.gradle.kts"), """
                plugins {
                    `java-platform`
                }
                group = "com.acme.platform"
                version = "1.0.0"
                dependencies {
                    api(platform("com.fasterxml.jackson:jackson-bom:2.18.2"))
                    constraints {
                        api("com.example:lib-a:1.2.0")
                        runtime("com.example:lib-b:3.4.5")
                    }
                }
                """);

        DraftManifestSubject subject = DraftManifestSubject.of(draft());

        assertTrue(subject.bom().isPresent(),
                () -> "a Kotlin-DSL java-platform project must draft a [bom] member: " + subject.manifest());
        assertEquals("2.18.2", subject.bomImports().get("com.fasterxml.jackson:jackson-bom"),
                () -> "Kotlin-DSL platform() import must become a [bom.imports] entry: "
                        + subject.bomImports());
        assertEquals("1.2.0", subject.bomVersions().get("com.example:lib-a"),
                () -> "Kotlin-DSL constraint must become a [bom.versions] pin: " + subject.bomVersions());
        assertEquals("3.4.5", subject.bomVersions().get("com.example:lib-b"),
                () -> "Kotlin-DSL runtime constraint must become a [bom.versions] pin: "
                        + subject.bomVersions());
    }

    @Test
    void catalogConstraintReferenceResolvesIntoBomVersions() throws IOException {
        Files.createDirectories(tempDir.resolve("gradle"));
        Files.writeString(tempDir.resolve("gradle/libs.versions.toml"), """
                [libraries]
                lib-a = { module = "com.example:lib-a", version = "1.2.0" }
                """);
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'acme-bom'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'java-platform'
                }
                group = 'com.acme.platform'
                version = '1.0.0'
                dependencies {
                    constraints {
                        api libs.lib.a
                    }
                }
                """);

        DraftManifestSubject subject = DraftManifestSubject.of(draft());

        assertEquals("1.2.0", subject.bomVersions().get("com.example:lib-a"),
                () -> "version-catalog constraint must resolve into [bom.versions]: "
                        + subject.bomVersions());
    }

    @Test
    void unparseableConstraintRaisesSignalAndIsNotSilentlyDropped() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'acme-bom'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'java-platform'
                }
                group = 'com.acme.platform'
                version = '1.0.0'
                dependencies {
                    constraints {
                        api "com.example:lib-a:${someComputedVersion}"
                    }
                }
                """);

        var result = new GradleStaticProjectInspector().inspect(tempDir);
        DraftManifestSubject subject = DraftManifestSubject.of(mapper.fromGradle(result));

        assertTrue(result.signals().stream().anyMatch(signal ->
                        signal.id().equals("gradle.dependency.dynamic-version")),
                () -> "an interpolated constraint must raise a signal rather than vanish: " + result.signals());
        assertTrue(subject.bomVersions().isEmpty(),
                () -> "the unresolved constraint must not be guessed into [bom.versions]: "
                        + subject.bomVersions());
    }

    private DraftZoltToml draft() throws IOException {
        return mapper.fromGradle(new GradleStaticProjectInspector().inspect(tempDir));
    }
}
