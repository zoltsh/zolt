package sh.zolt.explain.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GradleVersionCatalogInspectionTest {
    @TempDir
    private Path tempDir;

    private final GradleStaticProjectInspector inspector = new GradleStaticProjectInspector();

    @Test
    void resolvesRichVersionCatalogEntriesAndSignalsRanges() throws IOException {
        Files.createDirectories(tempDir.resolve("gradle"));
        Files.writeString(tempDir.resolve("gradle/libs.versions.toml"), """
                [versions]
                junit4 = { require = "[4.12,)", prefer = "4.13.2" }
                commons = { strictly = "[3.12,4[", prefer = "3.14.0" }

                [libraries]
                guava = { module = "com.google.guava:guava", version = { strictly = "[33.0, 34[", prefer = "33.4.8-jre" } }
                junit4 = { module = "junit:junit", version.ref = "junit4" }
                commons-lang3 = { module = "org.apache.commons:commons-lang3", version.ref = "commons" }
                logback = { module = "ch.qos.logback:logback-classic", version = { strictly = "1.5.6" } }
                """);
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins { id 'java' }
                dependencies {
                    implementation libs.guava
                    implementation libs.commons.lang3
                    implementation libs.logback
                    testImplementation libs.junit4
                }
                """);

        GradleInspectionResult result = inspector.inspect(tempDir);
        GradleProjectInspection project = result.projects().getFirst();

        assertTrue(project.dependencies().stream()
                .anyMatch(dependency -> dependency.versionCatalogAlias().equals("guava")
                        && dependency.resolvedCoordinate().equals("com.google.guava:guava:33.4.8-jre")));
        assertTrue(project.dependencies().stream()
                .anyMatch(dependency -> dependency.versionCatalogAlias().equals("junit4")
                        && dependency.resolvedCoordinate().equals("junit:junit:4.13.2")));
        assertTrue(project.dependencies().stream()
                .anyMatch(dependency -> dependency.versionCatalogAlias().equals("commons.lang3")
                        && dependency.resolvedCoordinate().equals("org.apache.commons:commons-lang3:3.14.0")));
        assertTrue(project.dependencies().stream()
                .anyMatch(dependency -> dependency.versionCatalogAlias().equals("logback")
                        && dependency.resolvedCoordinate().equals("ch.qos.logback:logback-classic:1.5.6")));
        assertEquals(
                3,
                result.signals().stream()
                        .filter(signal -> signal.id().equals("gradle.dependency.dynamic-version"))
                        .filter(signal -> signal.message().contains("version-policy rule: version-range"))
                        .count());
        assertFalse(result.signals().stream()
                .anyMatch(signal -> signal.message().contains("logback")));
    }

    @Test
    void resolvesKotlinPluginAliasesBeforeMigrationClassification() throws IOException {
        Path jvmProject = tempDir.resolve("jvm");
        Files.createDirectories(jvmProject.resolve("gradle"));
        Files.createDirectories(jvmProject.resolve("app"));
        Files.createDirectories(jvmProject.resolve("id-only"));
        Files.writeString(jvmProject.resolve("gradle/libs.versions.toml"), """
                [versions]
                kotlin-version = "2.2.0"

                [plugins]
                kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin_version" }
                kotlin_jvm_alt = { id = "org.jetbrains.kotlin.jvm", version = { ref = "kotlin.version" } }
                kotlin-id-only = { id = "org.jetbrains.kotlin.jvm" }
                java-core = { id = "java" }
                spotless = " com.diffplug.spotless : 7.0.0 "
                rich-plugin = { id = "com.example.rich", version = { prefer = "1.2.3" } }
                """);
        Files.writeString(jvmProject.resolve("settings.gradle.kts"), """
                rootProject.name = "jvm"
                include(":app", ":id-only")
                """);
        Files.writeString(jvmProject.resolve("app/build.gradle.kts"), """
                plugins {
                    alias(libs.plugins.kotlin.jvm)
                    alias(libs.plugins.kotlin.jvm.alt)
                    alias libs.plugins.spotless apply false
                    alias(libs.plugins.rich.plugin)
                }
                """);
        Files.writeString(jvmProject.resolve("id-only/build.gradle"), """
                plugins {
                    alias libs.plugins.kotlin.id.only
                    alias libs.plugins.java.core
                }
                """);

        GradleInspectionResult jvm = inspector.inspect(jvmProject);
        GradleProjectInspection app = jvm.projects().stream()
                .filter(project -> project.path().equals(Path.of("app")))
                .findFirst()
                .orElseThrow();
        GradleProjectInspection idOnly = jvm.projects().stream()
                .filter(project -> project.path().equals(Path.of("id-only")))
                .findFirst()
                .orElseThrow();

        assertTrue(app.plugins().contains(
                new GradlePluginInspection("org.jetbrains.kotlin.jvm", "2.2.0")));
        assertTrue(app.plugins().contains(
                new GradlePluginInspection("com.diffplug.spotless", "7.0.0")));
        assertTrue(app.plugins().contains(
                new GradlePluginInspection("com.example.rich", "1.2.3")));
        assertTrue(idOnly.plugins().contains(
                new GradlePluginInspection("org.jetbrains.kotlin.jvm", "")));
        assertTrue(idOnly.plugins().contains(new GradlePluginInspection("java", "")));
        assertTrue(jvm.versionCatalogAliases().isEmpty());
        assertTrue(jvm.signals().stream().anyMatch(signal ->
                signal.id().equals("gradle.kotlin.manual-migration")
                        && signal.project().equals("app")));
        assertTrue(jvm.signals().stream().anyMatch(signal ->
                signal.id().equals("gradle.kotlin.manual-migration")
                        && signal.project().equals("id-only")));
        assertFalse(jvm.signals().stream().anyMatch(signal ->
                signal.id().equals("gradle.plugin-alias.unresolved")
                        || signal.id().equals("gradle.language.unsupported")));

        Path multiplatformProject = tempDir.resolve("multiplatform");
        Files.createDirectories(multiplatformProject.resolve("gradle"));
        Files.writeString(multiplatformProject.resolve("gradle/libs.versions.toml"), """
                [plugins]
                kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version = " 2.2.0 " }
                """);
        Files.writeString(
                multiplatformProject.resolve("settings.gradle.kts"),
                "rootProject.name = \"multiplatform\"\n");
        Files.writeString(multiplatformProject.resolve("build.gradle.kts"), """
                plugins {
                    alias(libs.plugins.kotlin.multiplatform)
                }
                """);

        GradleInspectionResult multiplatform = inspector.inspect(multiplatformProject);

        assertTrue(multiplatform.projects().getFirst().plugins().contains(
                new GradlePluginInspection("org.jetbrains.kotlin.multiplatform", "2.2.0")));
        assertTrue(multiplatform.signals().stream().anyMatch(signal ->
                signal.id().equals("gradle.language.unsupported")));
        assertFalse(multiplatform.signals().stream().anyMatch(signal ->
                signal.id().equals("gradle.kotlin.manual-migration")
                        || signal.id().equals("gradle.plugin-alias.unresolved")));
    }

    @Test
    void leavesMalformedMissingAndCollidingPluginAliasesUnresolved() throws IOException {
        Files.createDirectories(tempDir.resolve("gradle"));
        Files.writeString(tempDir.resolve("gradle/libs.versions.toml"), """
                [versions]
                kotlin = "2.2.0"
                duplicate-version = "2.2.0"
                duplicate_version = "2.3.0"

                [libraries]
                collision-lib = { module = "com.example:collision", version.ref = "duplicate-version" }

                [plugins]
                bad-bare = "org.jetbrains.kotlin.jvm"
                missing-ref = { id = "org.jetbrains.kotlin.jvm", version.ref = "missing" }
                kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
                kotlin_jvm = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
                same-plugin = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
                same_plugin = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
                mixed-plugin = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
                mixed_plugin = "org.jetbrains.kotlin.jvm"
                blank-string = " : "
                version-collision = { id = "org.jetbrains.kotlin.jvm", version.ref = "duplicate.version" }
                """);
        Files.writeString(tempDir.resolve("settings.gradle.kts"), "rootProject.name = \"invalid-aliases\"\n");
        Files.writeString(tempDir.resolve("build.gradle.kts"), """
                plugins {
                    alias(libs.plugins.bad.bare)
                    alias(libs.plugins.missing.ref)
                    alias(libs.plugins.kotlin.jvm)
                    alias(libs.plugins.same.plugin)
                    alias(libs.plugins.mixed.plugin)
                    alias(libs.plugins.blank.string)
                    alias(libs.plugins.version.collision)
                    alias libs.plugins.not.present
                    alias tools.plugins.kotlin.jvm
                }
                dependencies {
                    implementation libs.collision.lib
                }
                """);

        GradleInspectionResult result = inspector.inspect(tempDir);

        assertEquals(9, result.signals().stream()
                .filter(signal -> signal.id().equals("gradle.plugin-alias.unresolved"))
                .count());
        assertTrue(result.projects().getFirst().plugins().isEmpty());
        assertTrue(result.versionCatalogAliases().isEmpty());
        assertEquals(1, result.projects().getFirst().dependencies().size());
        assertEquals("", result.projects().getFirst().dependencies().getFirst().resolvedCoordinate());
        assertTrue(result.signals().stream().anyMatch(signal ->
                signal.id().equals("gradle.dependency.unresolved-notation")
                        && signal.message().contains("libs.collision.lib")));
        assertFalse(result.signals().stream().anyMatch(signal ->
                signal.id().equals("gradle.kotlin.manual-migration")
                        || signal.id().equals("gradle.language.unsupported")));
    }
}
