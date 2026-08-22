package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.explain.maven.MavenStaticProjectInspector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MavenBomEmitTest {
    private final InspectionToManifest mapper = new InspectionToManifest();

    @Test
    void draftsBomMemberFromStandaloneDependencyManagementPom(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme.platform</groupId>
                  <artifactId>acme-bom</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.postgresql</groupId>
                        <artifactId>postgresql</artifactId>
                        <version>42.7.4</version>
                      </dependency>
                      <dependency>
                        <groupId>com.fasterxml.jackson</groupId>
                        <artifactId>jackson-bom</artifactId>
                        <version>2.17.0</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);

        DraftZoltToml draft = mapper.fromMaven(new MavenStaticProjectInspector().inspect(tempDir));
        DraftManifestSubject subject = DraftManifestSubject.of(draft);

        assertTrue(subject.bom().isPresent(),
                () -> "a dependencyManagement-only POM must draft a [bom] member: " + subject.manifest());
        // The plain pin becomes a [bom.versions] entry.
        assertEquals("42.7.4", subject.bomVersions().get("org.postgresql:postgresql"),
                () -> "plain dependencyManagement pin must become a [bom.versions] entry: "
                        + subject.bomVersions());
        // The import-scope BOM becomes a [bom.imports] entry.
        assertEquals("2.17.0", subject.bomImports().get("com.fasterxml.jackson:jackson-bom"),
                () -> "import-scope BOM must become a [bom.imports] entry: " + subject.bomImports());
        // A BOM declares no dependencies.
        assertTrue(subject.manifest().dependencies().isEmpty(),
                () -> "a drafted BOM must carry no [dependencies]: " + subject.manifest().dependencies());
    }
}
