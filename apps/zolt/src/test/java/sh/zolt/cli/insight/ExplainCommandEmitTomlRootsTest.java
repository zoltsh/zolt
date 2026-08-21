package sh.zolt.cli.insight;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredResources;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ExplainCommandEmitTomlRootsTest {
    @TempDir
    private Path tempDir;

    @Test
    void emittedMavenDraftCarriesAuditedSourceAndResourceRoots() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>custom-roots</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
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

        CommandResult result = execute("explain", "--emit-toml", "--cwd", tempDir.toString(), "--source", "maven");

        assertEquals(0, result.exitCode(), () -> result.stderr());
        assertTrue(result.stdout().contains("[build]"), () -> result.stdout());
        assertTrue(result.stdout().contains("sources = [\"src/gen\", \"src/java\"]"), () -> result.stdout());
        assertTrue(result.stdout().contains("[resources]"), () -> result.stdout());
        assertTrue(result.stdout().contains("main = [\"config\"]"), () -> result.stdout());
        assertTrue(result.stdout().contains("test = [\"test-config\"]"), () -> result.stdout());
        // The final language derives the test root from the build convention, so a non-conventional
        // Maven testSourceDirectory is review data rather than an authored key.
        assertFalse(result.stdout().contains("\"src/tests\""), () -> result.stdout());
        assertTrue(
                result.stdout().contains("Test sources live outside the Zolt convention `src/test/java`")
                        && result.stdout().contains("src/tests"),
                () -> result.stdout());

        AuthoredManifest parsed = new ManifestProjectConfigLoader().document(result.stdout()).authored();
        assertEquals(List.of("src/gen", "src/java"), paths(parsed.build().build().orElseThrow().sources()));
        AuthoredResources resources = parsed.build().resources().orElseThrow();
        assertEquals(List.of("config"), paths(resources.main()));
        assertEquals(List.of("test-config"), paths(resources.test()));
    }

    private static List<String> paths(List<ManifestRelativePath> roots) {
        return roots.stream().map(ManifestRelativePath::value).toList();
    }
}
