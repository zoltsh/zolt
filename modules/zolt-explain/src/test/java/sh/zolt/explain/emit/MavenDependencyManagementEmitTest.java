package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.explain.maven.MavenInspectionResult;
import sh.zolt.explain.maven.MavenProjectInspection;
import sh.zolt.explain.maven.MavenStaticProjectInspector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MavenDependencyManagementEmitTest {
    @TempDir
    private Path tempDir;

    private final InspectionToManifest mapper = new InspectionToManifest();

    @Test
    void emitsConstraintsPlatformManagedDepsAndClassifierReviewNotes() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>managed-demo</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.junit</groupId>
                        <artifactId>junit-bom</artifactId>
                        <version>5.10.2</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                      <dependency>
                        <groupId>org.apiguardian</groupId>
                        <artifactId>apiguardian-api</artifactId>
                        <version>1.1.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.jacoco</groupId>
                      <artifactId>org.jacoco.agent</artifactId>
                      <version>0.8.12</version>
                      <classifier>runtime</classifier>
                    </dependency>
                  </dependencies>
                </project>
                """);

        DraftZoltToml draft = mapper.fromMaven(new MavenStaticProjectInspector().inspect(tempDir));
        DraftManifestSubject subject = DraftManifestSubject.of(draft);

        assertEquals("5.10.2", subject.platforms().get("org.junit:junit-bom"),
                () -> "import-scope BOM must land in [platforms]: " + subject.platforms());
        assertTrue(subject.managed(DependencyLane.TEST).contains("org.junit.jupiter:junit-jupiter"),
                () -> "version-less junit dependency should use the platform-managed marker: "
                        + subject.managed(DependencyLane.TEST));
        assertFalse(subject.fixed(DependencyLane.TEST).containsKey("org.junit.jupiter:junit-jupiter"),
                () -> "platform-managed dependency must not get a guessed version: "
                        + subject.fixed(DependencyLane.TEST));
        assertEquals("1.1.0", subject.constraints().get("org.apiguardian:apiguardian-api"),
                () -> "unconsumed dependencyManagement pin must become a [dependencies.constraints] entry: "
                        + subject.constraints());
        assertFalse(
                subject.coordinates(DependencyLane.IMPLEMENTATION).contains("org.jacoco:org.jacoco.agent"),
                () -> "classifier dependency must not be emitted as the plain artifact: "
                        + subject.coordinates(DependencyLane.IMPLEMENTATION));
        assertTrue(
                draft.notes().stream().anyMatch(note ->
                        note.contains("org.jacoco:org.jacoco.agent")
                                && note.contains("classifier `runtime`")),
                () -> "classifier dependency needs an explicit review note: " + draft.notes());
    }

    @Test
    void versionlessDependencyWithoutEmittedPlatformKeepsReviewComment() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>unmanaged-demo</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>needs-version</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

        DraftZoltToml draft = mapper.fromMaven(new MavenStaticProjectInspector().inspect(tempDir));
        DraftManifestSubject subject = DraftManifestSubject.of(draft);

        assertTrue(subject.managed(DependencyLane.IMPLEMENTATION).isEmpty(),
                () -> "no platform present: version-less dep must not be managed: "
                        + subject.managed(DependencyLane.IMPLEMENTATION));
        assertTrue(
                draft.notes().stream().anyMatch(note ->
                        note.contains("com.example:needs-version")
                                && note.contains("no static version")),
                () -> "unmanaged version-less dependency should stay a review item: " + draft.notes());
    }

    @Test
    void snapshotImportedBomBecomesReviewNoteNotLivePlatform() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>snapshot-platform-demo</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>snapshot-bom</artifactId>
                        <version>1.0.0-SNAPSHOT</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);

        DraftZoltToml draft = mapper.fromMaven(new MavenStaticProjectInspector().inspect(tempDir));
        DraftManifestSubject subject = DraftManifestSubject.of(draft);

        assertFalse(subject.platforms().containsKey("com.example:snapshot-bom"),
                () -> "SNAPSHOT platform must not be emitted live: " + subject.platforms());
        assertTrue(draft.notes().stream()
                .anyMatch(note -> note.contains("com.example:snapshot-bom")
                        && note.contains("unsupported platform version")
                        && note.contains("snapshot-version")),
                () -> "SNAPSHOT platform should become a review note: " + draft.notes());
    }

    @Test
    void reactorMemberInheritsParentImportedBomIntoPlatforms() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>root</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>service</module>
                  </modules>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.junit</groupId>
                        <artifactId>junit-bom</artifactId>
                        <version>5.10.2</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        Path service = tempDir.resolve("service");
        Files.createDirectories(service);
        Files.writeString(service.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>root</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>service</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);

        MavenInspectionResult reactor = new MavenStaticProjectInspector().inspect(tempDir);
        MavenProjectInspection child = reactor.projects().stream()
                .filter(project -> project.artifactId().equals("service"))
                .findFirst()
                .orElseThrow();
        DraftZoltToml draft = mapper.fromMaven(new MavenInspectionResult(service, List.of(child), List.of()));
        DraftManifestSubject subject = DraftManifestSubject.of(draft);

        assertEquals("5.10.2", subject.platforms().get("org.junit:junit-bom"),
                () -> "inherited BOM must land in [platforms]: " + subject.platforms());
        assertTrue(subject.managed(DependencyLane.TEST).contains("org.junit.jupiter:junit-jupiter"),
                () -> "child should emit inherited BOM-managed test dependency: "
                        + subject.managed(DependencyLane.TEST));
    }
}
