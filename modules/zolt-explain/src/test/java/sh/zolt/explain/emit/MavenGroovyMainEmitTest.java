package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.explain.maven.MavenStaticProjectInspector;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MavenGroovyMainEmitTest {
    @TempDir
    private Path tempDir;

    @Test
    void emitsJavaAndGroovyMainRootsWithTheExplicitGroovyDependency() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.createDirectories(tempDir.resolve("src/main/groovy/com/example"));
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>groovy-main</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.groovy</groupId>
                      <artifactId>groovy</artifactId>
                      <version>4.0.22</version>
                    </dependency>
                  </dependencies>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.codehaus.gmavenplus</groupId>
                        <artifactId>gmavenplus-plugin</artifactId>
                        <version>3.0.2</version>
                        <executions>
                          <execution>
                            <goals>
                              <goal>compile</goal>
                              <goal>compileTests</goal>
                            </goals>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        DraftZoltToml draft = new InspectionToManifest()
                .fromMaven(new MavenStaticProjectInspector().inspect(tempDir));

        AuthoredBuild build = draft.manifest().build().build().orElseThrow();
        assertEquals(
                List.of("src/main/java", "src/main/groovy"),
                build.sources().stream().map(ManifestRelativePath::value).toList());
        assertEquals(
                "4.0.22",
                DraftManifestSubject.of(draft)
                        .fixed(DependencyLane.IMPLEMENTATION)
                        .get("org.apache.groovy:groovy"));
    }
}
