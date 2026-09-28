package sh.zolt.explain.gradle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GradleDynamicSignalDetectorTest {
    @TempDir
    private Path tempDir;

    private final GradleStaticProjectInspector inspector = new GradleStaticProjectInspector();

    @Test
    void reportsEnvironmentDrivenGradleLogicWithoutPromotingConditionalIncludesToMembers() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), """
                rootProject.name = 'env-driven'

                if (System.getenv('ANDROID_HOME') != null) {
                    include ':android'
                }
                """);
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                }

                version = System.getenv('BUILD_TAG') ?: 'local'

                java {
                    toolchain {
                        languageVersion = providers.environmentVariable('JDK_EXPERIMENTAL')
                                .map(Integer::parseInt)
                                .map(JavaLanguageVersion::of)
                                .getOrElse(JavaLanguageVersion.of(21))
                    }
                }

                if (System.getenv('CI') != null) {
                    apply plugin: 'jacoco'
                }

                apply from: 'gradle/quality.gradle'

                gradle.startParameter.excludedTaskNames += 'test'

                tasks.named('test', Test) {
                    jvmArgumentProviders.add(provider { ['-Dci=' + System.getenv('CI')] })
                }

                tasks.named('spotlessCheck').configure {
                    enabled = false
                }
                """);

        GradleInspectionResult result = inspector.inspect(tempDir);

        assertTrue(result.includedProjects().isEmpty());
        assertSignalIds(
                result,
                "gradle.environment-variable.read",
                "gradle.settings.include-conditional",
                "gradle.plugin.conditional-apply",
                "gradle.script-plugin.apply-from",
                "gradle.start-parameter.mutation",
                "gradle.task-mutation.detected",
                "gradle.test-runtime-settings");
        assertTrue(result.signals().stream().anyMatch(signal -> signal.id().equals("gradle.settings.include-conditional")
                && signal.message().contains(":android")));
        assertTrue(result.signals().stream().noneMatch(signal -> signal.id().equals("gradle.project.missing-build-file")
                && signal.project().equals("android")));
    }

    @Test
    void reportsWidenedPublicationConventionAndTestRuntimeSignalsWithoutBlockingGroovyMain() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/groovy/com/example"));
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'widened-gradle-signals'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'junitbuild.build-metadata'
                    id 'caffeine.publish'
                }

                tasks.named('test', Test) {
                    jvmArgumentProviders.add(provider { ['-Dexample=true'] })
                }

                publishing {
                    publications {
                        create("library", MavenPublication) {
                            from components.java
                        }
                    }
                }
                """);

        GradleInspectionResult result = inspector.inspect(tempDir);

        assertSignalIds(
                result,
                "gradle.plugin.convention",
                "gradle.publication.detected",
                "gradle.test-runtime-settings");
        assertFalse(
                result.signals().stream().anyMatch(signal -> signal.id().equals("gradle.language.unsupported")),
                () -> "Groovy main sources are supported and must not block migration: " + result.signals());
    }

    private static void assertSignalIds(GradleInspectionResult result, String... expectedIds) {
        for (String expectedId : expectedIds) {
            assertTrue(
                    result.signals().stream().anyMatch(signal -> signal.id().equals(expectedId)),
                    () -> "missing signal " + expectedId + " in " + result.signals());
        }
    }
}
