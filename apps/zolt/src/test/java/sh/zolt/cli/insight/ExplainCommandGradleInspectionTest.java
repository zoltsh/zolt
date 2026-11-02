package sh.zolt.cli.insight;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ExplainCommandGradleInspectionTest {
    @TempDir
    private Path tempDir;

    @Test
    void explainGradleJsonInspectsBuildStatically() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'demo'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins { id 'java' }
                repositories { mavenCentral() }
                dependencies { implementation 'com.google.guava:guava:33.4.8-jre' }
                """);

        CommandResult result = execute(
                "explain",
                "--cwd", tempDir.toString(),
                "--source", "gradle",
                "--format", "json");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().startsWith("{\n  \"schemaVersion\": 1,"));
        assertTrue(result.stdout().contains("\"source\": \"gradle\""));
        assertTrue(result.stdout().contains("\"root\": \"" + jsonPath(tempDir.toAbsolutePath().normalize()) + "\""));
        assertTrue(result.stdout().contains("\"resolvedCoordinate\": \"com.google.guava:guava:33.4.8-jre\""));
        assertEquals("", result.stderr());
    }

    @Test
    void explainGradleJsonCountsOkSignalsInSummary() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'demo'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'jacoco'
                }
                """);

        CommandResult result = execute(
                "explain",
                "--cwd", tempDir.toString(),
                "--source", "gradle",
                "--format", "json");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\"signals\": 2"));
        assertTrue(result.stdout().contains("\"blockers\": 0"));
        assertTrue(result.stdout().contains("\"warnings\": 0"));
        assertTrue(result.stdout().contains("\"unknown\": 0"));
        assertTrue(result.stdout().contains("\"ok\": 2"));
        assertEquals(2, countOccurrences(result.stdout(), "\"severity\": \"ok\""));
        assertEquals("", result.stderr());
    }

    @Test
    void explainGradleClassifiesUnsupportedBetaShapes() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'demo'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'org.jetbrains.kotlin.jvm' version '2.2.0'
                    id 'com.android.application' version '8.8.0'
                    id 'org.graalvm.buildtools.native' version '0.10.6'
                }
                repositories { mavenCentral() }
                dependencies { implementation 'com.example:dynamic-lib:1.+' }
                tasks.named('bootBuildImage') { }
                """);

        CommandResult result = execute(
                "explain",
                "--cwd", tempDir.toString(),
                "--source", "gradle",
                "--format", "json");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\"id\": \"gradle.kotlin.manual-migration\""));
        assertFalse(result.stdout().contains("\"id\": \"gradle.language.unsupported\""));
        assertTrue(result.stdout().contains("\"id\": \"gradle.android.unsupported\""));
        assertTrue(result.stdout().contains("\"id\": \"gradle.framework-native.unsupported\""));
        assertTrue(result.stdout().contains("\"id\": \"gradle.dependency.dynamic-version\""));
        assertTrue(result.stdout().contains("\"status\": \"blocked\""));
        assertEquals("", result.stderr());
    }

    @Test
    void explainGradleClassifiesKotlinJvmAsManualReview() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle.kts"), "rootProject.name = \"kotlin-app\"\n");
        Files.writeString(tempDir.resolve("build.gradle.kts"), """
                plugins {
                    kotlin("jvm") version "2.2.0"
                }
                """);

        CommandResult result = execute(
                "explain",
                "--cwd", tempDir.toString(),
                "--source", "gradle",
                "--format", "json");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\"blockers\": 0"));
        assertTrue(result.stdout().contains("\"warnings\": 1"));
        assertTrue(result.stdout().contains("\"id\": \"gradle.kotlin.manual-migration\""));
        assertTrue(result.stdout().contains("\"status\": \"manual-review\""));
        assertFalse(result.stdout().contains("\"id\": \"gradle.language.unsupported\""));
        assertEquals("", result.stderr());
    }

    @Test
    void explainGradleKeepsUnresolvedPluginAliasesUnknown() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle.kts"), "rootProject.name = \"unknown-plugin\"\n");
        Files.writeString(tempDir.resolve("build.gradle.kts"), """
                plugins {
                    alias(libs.plugins.kotlin.jvm)
                }
                """);

        CommandResult result = execute(
                "explain",
                "--cwd", tempDir.toString(),
                "--source", "gradle",
                "--format", "json");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\"blockers\": 0"));
        assertTrue(result.stdout().contains("\"unknown\": 1"));
        assertTrue(result.stdout().contains("\"plugins\": []"));
        assertTrue(result.stdout().contains("\"id\": \"gradle.plugin-alias.unresolved\""));
        assertTrue(result.stdout().contains("\"status\": \"manual-review\""));
        assertEquals("", result.stderr());
    }

    @Test
    void explainGradleScorecardJsonReportsReadinessConcerns() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'demo'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'jacoco'
                    id 'org.openapi.generator' version '7.11.0'
                }
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
                configurations.all {
                    resolutionStrategy.force 'com.google.guava:guava:33.4.8-jre'
                }
                tasks.register('generateApi') { }
                sourceSets {
                    main {
                        java {
                            srcDirs += "${buildDir}/generated/api".toString()
                        }
                    }
                }
                dependencies {
                    implementation 'com.google.guava:guava:33.4.8-jre'
                }
                """);

        CommandResult result = execute(
                "explain",
                "--cwd", tempDir.toString(),
                "--source", "gradle",
                "--scorecard",
                "--format", "json");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\"command\": \"explain-scorecard\""));
        assertTrue(result.stdout().contains("\"source\": \"gradle\""));
        assertTrue(result.stdout().contains("\"name\": \"repositories\""));
        assertTrue(result.stdout().contains("\"status\": \"non-deterministic\""));
        assertTrue(result.stdout().contains("\"category\": \"blocked\""));
        assertTrue(result.stdout().contains("\"sourcePattern\": \"mavenLocal() property switch\""));
        assertTrue(result.stdout().contains("\"zoltPrimitive\": \"local repository overlays\""));
        assertTrue(result.stdout().contains("\"followUp\": \"\""));
        assertEquals("", result.stderr());
    }

    @Test
    void explainGradleBlockerJsonReportsZoltNativeFollowUps() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'demo'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins { id 'java' }
                def token = findProperty('repoToken') ?: System.getenv('REPO_TOKEN') ?: 'dummy'
                repositories {
                    mavenLocal()
                    maven {
                        url = 'https://repo.example.invalid/maven'
                        credentials {
                            username = 'ci'
                            password = token
                        }
                    }
                }
                configurations.all {
                    exclude group: 'commons-logging', module: 'commons-logging'
                }
                tasks.named('jar') {
                    exclude('BOOT-INF/lib/tomcat-*.jar')
                }
                dependencies {
                    implementation 'com.google.guava:guava:33.4.8-jre'
                }
                """);

        CommandResult result = execute(
                "explain",
                "--cwd", tempDir.toString(),
                "--source", "gradle",
                "--blockers",
                "--format", "json");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\"command\": \"explain-blockers\""));
        assertTrue(result.stdout().contains("\"status\": \"blocked\""));
        assertTrue(result.stdout().contains("\"sourcePattern\": \"credentials resolved from Gradle properties, env, or defaults\""));
        assertTrue(result.stdout().contains("\"zoltPrimitive\": \"[repositories] credential identities\""));
        assertTrue(result.stdout().contains("\"followUp\": \"\""));
        assertTrue(result.stdout().contains("\"sourcePattern\": \"configurations.all, excludes, force, or resolutionStrategy\""));
        assertTrue(result.stdout().contains("\"followUp\": \"\""));
        assertFalse(result.stdout().contains("dummy"));
        assertFalse(result.stdout().contains("REPO_TOKEN"));
        assertEquals("", result.stderr());
    }

    private static String jsonPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while (true) {
            int index = value.indexOf(needle, offset);
            if (index < 0) {
                return count;
            }
            count++;
            offset = index + needle.length();
        }
    }
}
