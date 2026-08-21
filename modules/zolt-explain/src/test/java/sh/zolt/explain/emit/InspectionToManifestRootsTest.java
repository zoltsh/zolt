package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.explain.gradle.GradleStaticProjectInspector;
import sh.zolt.explain.maven.MavenStaticProjectInspector;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredBuild;
import sh.zolt.manifest.authored.AuthoredResources;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class InspectionToManifestRootsTest {
    @TempDir
    private Path tempDir;

    private final InspectionToManifest mapper = new InspectionToManifest();

    @Test
    void mavenDraftCarriesAuditedBuildRootsIntoBuildSettings() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>custom-roots</artifactId>
                  <version>1.0.0</version>
                  <build>
                    <sourceDirectory>src/java</sourceDirectory>
                    <testSourceDirectory>src/tests</testSourceDirectory>
                    <resources>
                      <resource>
                        <directory>config</directory>
                      </resource>
                    </resources>
                    <testResources>
                      <testResource>
                        <directory>test-config</directory>
                      </testResource>
                    </testResources>
                    <plugins>
                      <plugin>
                        <groupId>org.codehaus.mojo</groupId>
                        <artifactId>build-helper-maven-plugin</artifactId>
                        <executions>
                          <execution>
                            <goals>
                              <goal>add-source</goal>
                            </goals>
                            <configuration>
                              <sources>
                                <source>src/gen</source>
                              </sources>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        DraftZoltToml draft = mapper.fromMaven(new MavenStaticProjectInspector().inspect(tempDir));

        AuthoredBuild build = draft.manifest().build().build().orElseThrow();
        assertEquals(List.of("src/gen", "src/java"), paths(build.sources()));
        AuthoredResources resources = draft.manifest().build().resources().orElseThrow();
        assertEquals(List.of("config"), paths(resources.main()));
        assertEquals(List.of("test-config"), paths(resources.test()));
        // The final language derives the test root from the build convention, so a non-conventional
        // Maven testSourceDirectory has no authored counterpart; it survives as a review note.
        assertTrue(
                draft.notes().stream().anyMatch(note ->
                        note.contains("Test sources live outside") && note.contains("src/tests")),
                () -> "expected the non-conventional test root review note: " + draft.notes());
    }

    @Test
    void mavenDraftCarriesUnsupportedKotlinRootsAsAuditedReviewData() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>kotlin-roots</artifactId>
                  <version>1.0.0</version>
                  <build>
                    <sourceDirectory>src/main/kotlin</sourceDirectory>
                    <testSourceDirectory>src/test/kotlin</testSourceDirectory>
                  </build>
                </project>
                """);

        DraftZoltToml draft = mapper.fromMaven(new MavenStaticProjectInspector().inspect(tempDir));

        AuthoredBuild build = draft.manifest().build().build().orElseThrow();
        assertEquals(List.of("src/main/kotlin"), paths(build.sources()));
        assertTrue(
                draft.notes().stream().anyMatch(note ->
                        note.contains("Test sources live outside") && note.contains("src/test/kotlin")),
                () -> "expected the audited Kotlin test root as review data: " + draft.notes());
    }

    @Test
    void gradleDraftCarriesAuditedSourceRootsIntoBuildSettings() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'custom-roots'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins { id 'java' }
                group = 'com.example'
                version = '1.0.0'
                sourceSets {
                    main {
                        java {
                            srcDirs = ['src/java', 'src/generated/java']
                        }
                    }
                    test {
                        java {
                            srcDirs = ['src/tests', 'src/fixtures']
                        }
                    }
                }
                """);

        DraftZoltToml draft = mapper.fromGradle(new GradleStaticProjectInspector().inspect(tempDir));

        AuthoredBuild build = draft.manifest().build().build().orElseThrow();
        assertEquals(List.of("src/generated/java", "src/java"), paths(build.sources()));
        assertTrue(
                draft.notes().stream().anyMatch(note ->
                        note.contains("Test sources live outside")
                                && note.contains("src/tests")
                                && note.contains("src/fixtures")),
                () -> "expected the non-conventional test root review note: " + draft.notes());
    }

    /** {@code [build].sources} is a sorted, distinct path list in the authored model. */
    private static List<String> paths(List<ManifestRelativePath> roots) {
        return roots.stream().map(ManifestRelativePath::value).toList();
    }
}
