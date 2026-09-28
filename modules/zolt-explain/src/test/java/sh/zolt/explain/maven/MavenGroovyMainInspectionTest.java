package sh.zolt.explain.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.explain.ExplainSignal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MavenGroovyMainInspectionTest {
    @TempDir
    private Path tempDir;

    private final MavenStaticProjectInspector inspector = new MavenStaticProjectInspector();

    @Test
    void preservesConventionalGroovyMainRootAndReplacesStandardGmavenplusCompilation() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.createDirectories(tempDir.resolve("src/main/groovy/com/example"));
        Files.writeString(tempDir.resolve("pom.xml"), pom("""
                <execution>
                  <goals>
                    <goal>compile</goal>
                    <goal>compileTests</goal>
                  </goals>
                </execution>
                """));

        MavenInspectionResult result = inspector.inspect(tempDir);
        MavenProjectInspection project = result.projects().getFirst();
        MavenPluginInspection gmavenplus = project.plugins().stream()
                .filter(plugin -> plugin.coordinate().contains(":gmavenplus-plugin:"))
                .findFirst()
                .orElseThrow();

        assertEquals(List.of("src/main/java", "src/main/groovy"), project.sourceRoots());
        assertEquals(List.of("compile", "test-compile"), gmavenplus.phases());
        assertEquals(List.of("compile", "compileTests"), gmavenplus.goals());
        assertTrue(project.dependencies().stream().anyMatch(dependency ->
                dependency.coordinate().equals("org.apache.groovy:groovy:4.0.22")
                        && dependency.scope().equals("compile")));
        assertFalse(
                result.signals().stream().anyMatch(signal ->
                        signal.message().contains("gmavenplus-plugin")),
                () -> "the standard Groovy compile pair is replaced by Zolt compilation: " + result.signals());
    }

    @Test
    void keepsArbitraryGmavenplusGoalsAndPhasesBlocked() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), pom("""
                <execution>
                  <id>interactive-console</id>
                  <goals>
                    <goal>console</goal>
                  </goals>
                </execution>
                <execution>
                  <id>misbound-compile</id>
                  <phase>verify</phase>
                  <goals>
                    <goal>compile</goal>
                  </goals>
                </execution>
                """));

        MavenInspectionResult result = inspector.inspect(tempDir);
        ExplainSignal blocker = result.signals().stream()
                .filter(signal -> signal.id().equals("maven.plugin.lifecycle-binding"))
                .filter(signal -> signal.message().contains("gmavenplus-plugin"))
                .findFirst()
                .orElseThrow();

        assertEquals(ExplainSignal.Severity.BLOCK, blocker.severity());
        assertTrue(blocker.message().contains("[compile, console]"), blocker::message);
        assertTrue(blocker.message().contains("[verify]"), blocker::message);
    }

    private static String pom(String executions) {
        return """
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
                %s
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(executions.indent(10).stripTrailing());
    }
}
