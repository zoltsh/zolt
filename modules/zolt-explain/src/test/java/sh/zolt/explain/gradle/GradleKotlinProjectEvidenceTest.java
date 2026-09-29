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

final class GradleKotlinProjectEvidenceTest {
    @TempDir
    private Path tempDir;

    private final GradleStaticProjectInspector inspector = new GradleStaticProjectInspector();

    @Test
    void recordsExactJavaToolchainWithoutInventingKotlinControls() throws IOException {
        GradleKotlinProjectEvidence evidence = inspect("""
                plugins {
                    id 'org.jetbrains.kotlin.jvm' version '2.3.21'
                }
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(21)
                    }
                }
                """);

        assertEquals("21", evidence.javaToolchainVersion());
        assertTrue(evidence.javaToolchainShapeProven());
        assertFalse(evidence.javaCompatibilityConfigured());
        assertFalse(evidence.kotlinExtensionConfigured());
        assertFalse(evidence.kotlinCompilerControlsConfigured());
        assertFalse(evidence.sourceSetsConfigured());
        assertEquals("", evidence.stdlibDefaultDependency());
        assertTrue(evidence.kotlinProperties().isEmpty());
        assertFalse(evidence.annotationProcessingConfigured());
        assertFalse(evidence.buildSrcPresent());
    }

    @Test
    void recordsConfigurationThatCanOverrideKotlinCompilation() throws IOException {
        Files.createDirectories(tempDir.resolve("buildSrc"));
        Files.writeString(tempDir.resolve("gradle.properties"), """
                kotlin.stdlib.default.dependency=false
                kotlin.jvm.target.validation.mode=warning
                """);
        GradleKotlinProjectEvidence evidence = inspect("""
                plugins {
                    id 'org.jetbrains.kotlin.jvm' version '2.3.21'
                    id 'org.jetbrains.kotlin.kapt' version '2.3.21'
                }
                java {
                    toolchain {
                        languageVersion.set(JavaLanguageVersion.of(21))
                        vendor.set(JvmVendorSpec.ADOPTIUM)
                    }
                    sourceCompatibility = JavaVersion.VERSION_21
                }
                kotlin {
                    compilerOptions {
                        allWarningsAsErrors = true
                    }
                }
                sourceSets {
                    main.kotlin.srcDir('generated/kotlin')
                }
                dependencies {
                    kapt 'com.google.dagger:dagger-compiler:2.56.2'
                }
                tasks.named('compileTestKotlin') {
                    enabled = false
                }
                """);

        assertEquals("21", evidence.javaToolchainVersion());
        assertFalse(evidence.javaToolchainShapeProven());
        assertTrue(evidence.javaCompatibilityConfigured());
        assertTrue(evidence.kotlinExtensionConfigured());
        assertTrue(evidence.kotlinCompilerControlsConfigured());
        assertTrue(evidence.sourceSetsConfigured());
        assertEquals("false", evidence.stdlibDefaultDependency());
        assertEquals(
                List.of(
                        "kotlin.jvm.target.validation.mode",
                        "kotlin.stdlib.default.dependency"),
                evidence.kotlinProperties());
        assertTrue(evidence.annotationProcessingConfigured());
        assertTrue(evidence.buildSrcPresent());
    }

    @Test
    void projectPropertyOverridesRootStdlibDefault() throws IOException {
        Files.createDirectories(tempDir.resolve("app"));
        Files.writeString(tempDir.resolve("settings.gradle"), """
                rootProject.name = 'root'
                include ':app'
                """);
        Files.writeString(tempDir.resolve("gradle.properties"),
                "kotlin.stdlib.default.dependency=false\n");
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }\n");
        Files.writeString(tempDir.resolve("app/gradle.properties"),
                "kotlin.stdlib.default.dependency=true\n");
        Files.writeString(tempDir.resolve("app/build.gradle"), """
                plugins {
                    id 'org.jetbrains.kotlin.jvm' version '2.3.21'
                }
                """);

        GradleProjectInspection app = inspector.inspect(tempDir).projects().stream()
                .filter(project -> project.path().equals(Path.of("app")))
                .findFirst()
                .orElseThrow();

        assertEquals("true", app.kotlinEvidence().stdlibDefaultDependency());
        assertEquals(
                List.of("kotlin.stdlib.default.dependency"),
                app.kotlinEvidence().kotlinProperties());
    }

    private GradleKotlinProjectEvidence inspect(String buildGradle) throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'demo'\n");
        Files.writeString(tempDir.resolve("build.gradle"), buildGradle);
        return inspector.inspect(tempDir).projects().getFirst().kotlinEvidence();
    }
}
