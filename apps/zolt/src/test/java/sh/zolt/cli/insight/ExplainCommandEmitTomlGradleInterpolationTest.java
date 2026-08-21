package sh.zolt.cli.insight;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ExplainCommandEmitTomlGradleInterpolationTest {
    @TempDir
    private Path tempDir;

    @Test
    void gradleDependencyVersionPlaceholdersEmitConcreteResolvableVersions() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("org.slf4j", "slf4j-api", "2.0.13", pom("org.slf4j", "slf4j-api", "2.0.13"));
            repository.addArtifact(
                    "com.google.code.gson",
                    "gson",
                    "2.11.0",
                    pom("com.google.code.gson", "gson", "2.11.0"));
            repository.addArtifact(
                    "org.junit.jupiter",
                    "junit-jupiter",
                    "5.10.2",
                    pom("org.junit.jupiter", "junit-jupiter", "5.10.2"));
            repository.addArtifact(
                    "org.junit.platform",
                    "junit-platform-console",
                    "1.10.2",
                    pom("org.junit.platform", "junit-platform-console", "1.10.2"));
            Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'interpolated'\n");
            Files.writeString(tempDir.resolve("gradle.properties"), """
                    group=com.acme
                    version=1.2.3
                    gsonVersion=2.11.0
                    """);
            Files.writeString(tempDir.resolve("build.gradle"), """
                    plugins { id 'java' }
                    sourceCompatibility = JavaVersion.VERSION_21
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

            CommandResult explain =
                    execute("explain", "--emit-toml", "--cwd", tempDir.toString(), "--source", "gradle");

            assertEquals(0, explain.exitCode(), () -> explain.stderr());
            assertFalse(explain.stdout().contains("$"), () -> explain.stdout());
            assertTrue(explain.stdout().contains("\"org.slf4j:slf4j-api\" = \"2.0.13\""), () -> explain.stdout());
            assertTrue(explain.stdout().contains("\"com.google.code.gson:gson\" = \"2.11.0\""), () -> explain.stdout());
            assertTrue(explain.stdout().contains("\"org.junit.jupiter:junit-jupiter\" = \"5.10.2\""), () -> explain.stdout());

            Files.writeString(tempDir.resolve("zolt.toml"), withCentralMirror(explain.stdout(), repository));
            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", tempDir.toString(),
                    "--cache-root", tempDir.resolve("cache").toString());

            assertEquals(0, resolve.exitCode(), () -> resolve.stderr());
        }
    }

    /**
     * The final language never authors {@code [repositories]} in a draft, so a draft inherits Maven
     * Central. Pointing a resolve at the hermetic test repository is an explicit Central mirror
     * appended to the emitted document.
     */
    private static String withCentralMirror(String manifest, CliTestRepository repository) {
        return manifest + """

                [repositories]
                central = "%s"
                """.formatted(repository.baseUri());
    }

    private static String pom(String group, String artifact, String version) {
        return """
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(group, artifact, version);
    }
}
