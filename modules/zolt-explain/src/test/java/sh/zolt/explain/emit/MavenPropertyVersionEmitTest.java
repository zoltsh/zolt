package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.explain.maven.MavenInspectionResult;
import sh.zolt.explain.maven.MavenStaticProjectInspector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Property-driven Maven versions in an emitted draft: {@code ${...}} tokens are interpolated to the
 * concrete versions the draft must carry, a resolvable property is never mistaken for a dynamic
 * version, and an unresolvable one becomes an honest review comment rather than a fabricated version.
 */
final class MavenPropertyVersionEmitTest {
    @TempDir
    private Path tempDir;

    private final InspectionToManifest mapper = new InspectionToManifest();

    @Test
    void mavenDraftInterpolatesPropertyDrivenVersions() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <jackson.version>2.17.1</jackson.version>
                    <junit.version>5.10.2</junit.version>
                  </properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.junit</groupId>
                        <artifactId>junit-bom</artifactId>
                        <version>${junit.version}</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>com.fasterxml.jackson.core</groupId>
                      <artifactId>jackson-databind</artifactId>
                      <version>${jackson.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        DraftZoltToml draft = mapper.fromMaven(new MavenStaticProjectInspector().inspect(tempDir));
        DraftManifestSubject subject = DraftManifestSubject.of(draft);

        assertEquals("2.17.1",
                subject.fixed(DependencyLane.IMPLEMENTATION).get("com.fasterxml.jackson.core:jackson-databind"));
        assertEquals("5.10.2", subject.platforms().get("org.junit:junit-bom"));
        assertFalse(
                subject.fixed(DependencyLane.IMPLEMENTATION).values().stream()
                        .anyMatch(version -> version.contains("${")),
                () -> "no interpolation tokens should survive in dependency versions: "
                        + subject.fixed(DependencyLane.IMPLEMENTATION));
    }

    @Test
    void mavenDraftWithPropertyVersionsReadsAsDeterministic() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <guava.version>33.2.1-jre</guava.version>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                      <version>${guava.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        MavenInspectionResult result = new MavenStaticProjectInspector().inspect(tempDir);

        assertTrue(
                result.signals().stream()
                        .noneMatch(signal -> signal.id().equals("maven.dependency.dynamic-version")),
                () -> "property-driven deterministic project must not be a SNAPSHOT/range blocker: "
                        + result.signals());
        DraftZoltToml draft = mapper.fromMaven(result);
        assertFalse(
                draft.notes().stream().anyMatch(note -> note.contains("SNAPSHOT") || note.contains("range")),
                () -> "no false SNAPSHOT/range review copy: " + draft.notes());
    }

    @Test
    void mavenDraftSurfacesUnresolvablePropertyAsReviewCommentNotBlocker() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>widget</artifactId>
                      <version>${undeclared.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        DraftZoltToml draft = mapper.fromMaven(new MavenStaticProjectInspector().inspect(tempDir));

        assertFalse(
                DraftManifestSubject.of(draft).coordinates(DependencyLane.IMPLEMENTATION)
                        .contains("com.example:widget"),
                () -> "an unresolved-property dependency must not be emitted as a real version");
        assertTrue(
                draft.notes().stream().anyMatch(note ->
                        note.contains("com.example:widget")
                                && note.contains("property")
                                && note.contains("could not resolve")),
                () -> "expected an honest review comment for the unresolved property: " + draft.notes());
    }
}
