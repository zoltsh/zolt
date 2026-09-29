package sh.zolt.explain.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GradleStaticProjectInspectorRootsTest {
    @TempDir
    private Path tempDir;

    private final GradleStaticProjectInspector inspector = new GradleStaticProjectInspector();

    @Test
    void omitsConventionSourceRootsThatDoNotExistOnDisk() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'bare'\n");
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }\n");

        GradleProjectInspection project = inspector.inspect(tempDir).projects().getFirst();

        assertEquals(List.of(), project.sourceRoots());
        assertEquals(List.of(), project.testSourceRoots());
    }

    @Test
    void keepsExplicitSourceSetRootsEvenWhenAbsent() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'explicit-roots'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins { id 'java' }
                sourceSets {
                    main {
                        java {
                            srcDirs = ['src/java']
                        }
                    }
                    test {
                        java {
                            srcDirs += ['src/tests']
                        }
                    }
                }
                """);

        GradleProjectInspection project = inspector.inspect(tempDir).projects().getFirst();

        assertEquals(List.of("src/java"), project.sourceRoots());
        assertEquals(List.of("src/tests"), project.testSourceRoots());
    }

    @Test
    void recognizesGroovyTestSourcesWithoutBlockingSpockGradleProjects() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.createDirectories(tempDir.resolve("src/test/groovy/com/example"));
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'spock-gradle'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'groovy'
                }

                dependencies {
                    testImplementation 'org.spockframework:spock-core:2.3-groovy-4.0'
                }
                """);

        GradleInspectionResult result = inspector.inspect(tempDir);
        GradleProjectInspection project = result.projects().getFirst();

        assertEquals(List.of("src/main/java"), project.sourceRoots());
        assertEquals(List.of(), project.testSourceRoots());
        assertEquals(List.of("src/test/groovy"), project.groovyTestSourceRoots());
        assertFalse(result.signals().stream().anyMatch(signal -> signal.id().equals("gradle.language.unsupported")),
                () -> "test-only Groovy should not block migration: " + result.signals());
    }

    @Test
    void preservesConventionalJavaAndGroovyMainRootsWithoutLanguageBlocker() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.createDirectories(tempDir.resolve("src/main/groovy/com/example"));
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'groovy-main'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'groovy'
                }

                dependencies {
                    implementation localGroovy()
                }
                """);

        GradleInspectionResult result = inspector.inspect(tempDir);
        GradleProjectInspection project = result.projects().getFirst();

        assertEquals(List.of("src/main/java", "src/main/groovy"), project.sourceRoots());
        assertFalse(
                result.signals().stream().anyMatch(signal -> signal.id().equals("gradle.language.unsupported")),
                () -> "Groovy main sources are supported and must not block migration: " + result.signals());
        assertTrue(
                result.signals().stream()
                        .anyMatch(signal -> signal.id().equals("gradle.dependency.unresolved-notation")
                                && signal.message().contains("localGroovy()")),
                () -> "localGroovy() must remain an explicit dependency review item: " + result.signals());
    }

    @Test
    void discoversConventionalKotlinJvmRootsInStableLanguageOrder() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.createDirectories(tempDir.resolve("src/main/groovy/com/example"));
        Files.createDirectories(tempDir.resolve("src/main/kotlin/com/example"));
        Files.createDirectories(tempDir.resolve("src/test/java/com/example"));
        Files.createDirectories(tempDir.resolve("src/test/groovy/com/example"));
        Files.createDirectories(tempDir.resolve("src/test/kotlin/com/example"));
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'mixed-kotlin'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'groovy'
                    id 'org.jetbrains.kotlin.jvm' version '2.2.20'
                }
                """);

        GradleInspectionResult result = inspector.inspect(tempDir);
        GradleProjectInspection project = result.projects().getFirst();

        assertEquals(
                List.of("src/main/java", "src/main/groovy", "src/main/kotlin"),
                project.sourceRoots());
        assertEquals(List.of("src/test/java", "src/test/kotlin"), project.testSourceRoots());
        assertEquals(List.of("src/test/groovy"), project.groovyTestSourceRoots());
        assertTrue(
                result.signals().stream()
                        .anyMatch(signal -> signal.id().equals("gradle.kotlin.manual-migration")),
                () -> "Kotlin roots must remain paired with the manual-migration signal: " + result.signals());
    }

    @Test
    void ignoresKotlinDirectoriesWithoutAnAppliedJvmPlugin() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/kotlin/com/example"));
        Files.createDirectories(tempDir.resolve("src/test/kotlin/com/example"));
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'stray-kotlin'\n");
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }\n");

        GradleInspectionResult result = inspector.inspect(tempDir);
        GradleProjectInspection project = result.projects().getFirst();

        assertEquals(List.of(), project.sourceRoots());
        assertEquals(List.of(), project.testSourceRoots());
        assertFalse(result.signals().stream()
                .anyMatch(signal -> signal.id().equals("gradle.kotlin.manual-migration")));
    }

    @Test
    void ignoresKotlinJvmConventionDeclaredApplyFalse() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/kotlin/com/example"));
        Files.createDirectories(tempDir.resolve("src/test/kotlin/com/example"));
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'inactive-kotlin'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'org.jetbrains.kotlin.jvm' version '2.2.20' apply false
                }
                """);

        GradleProjectInspection project = inspector.inspect(tempDir).projects().getFirst();

        assertEquals(List.of(), project.sourceRoots());
        assertEquals(List.of(), project.testSourceRoots());
    }

    @Test
    void ignoresKotlinJvmConventionForOtherKotlinPlatforms() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/kotlin/com/example"));
        Files.createDirectories(tempDir.resolve("src/test/kotlin/com/example"));
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'multiplatform-kotlin'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'org.jetbrains.kotlin.multiplatform' version '2.2.20'
                }
                """);

        GradleInspectionResult result = inspector.inspect(tempDir);
        GradleProjectInspection project = result.projects().getFirst();

        assertEquals(List.of(), project.sourceRoots());
        assertEquals(List.of(), project.testSourceRoots());
        assertTrue(result.signals().stream()
                .anyMatch(signal -> signal.id().equals("gradle.language.unsupported")));
    }

    @Test
    void doesNotInferKotlinConventionAcrossExplicitSourceSets() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/kotlin/com/example"));
        Files.createDirectories(tempDir.resolve("src/test/kotlin/com/example"));
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'custom-kotlin-roots'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'org.jetbrains.kotlin.jvm' version '2.2.20'
                }
                kotlin.sourceSets.named('main') {
                    kotlin.setSrcDirs(['src/custom/kotlin'])
                }
                kotlin.sourceSets.named('test') {
                    kotlin.setSrcDirs(['src/custom-test/kotlin'])
                }
                """);

        GradleProjectInspection project = inspector.inspect(tempDir).projects().getFirst();

        assertEquals(List.of(), project.sourceRoots());
        assertEquals(List.of(), project.testSourceRoots());
    }
}
