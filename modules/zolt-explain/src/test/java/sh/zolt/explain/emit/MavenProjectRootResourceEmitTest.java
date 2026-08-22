package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.explain.maven.MavenStaticProjectInspector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MavenProjectRootResourceEmitTest {
    @TempDir
    private Path tempDir;

    private final InspectionToManifest mapper = new InspectionToManifest();

    @Test
    void dropsBareProjectRootResourceRootAndAnnotatesIt() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/resources"));
        Files.createDirectories(tempDir.resolve("src/main/java/com/ex"));
        Files.writeString(tempDir.resolve("src/main/java/com/ex/App.java"), "package com.ex; public class App { }\n");
        Files.writeString(tempDir.resolve("LICENSE"), "license text\n");
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <packaging>jar</packaging>
                  <build>
                    <resources>
                      <resource>
                        <directory>src/main/resources</directory>
                        <filtering>false</filtering>
                      </resource>
                      <resource>
                        <directory>./</directory>
                        <targetPath>META-INF/demo/</targetPath>
                        <filtering>false</filtering>
                        <includes>
                          <include>LICENSE</include>
                        </includes>
                      </resource>
                    </resources>
                  </build>
                </project>
                """);

        DraftZoltToml draft = mapper.fromMaven(new MavenStaticProjectInspector().inspect(tempDir));

        // The project root is dropped, leaving only the Zolt convention `src/main/resources`, which the
        // sparse manifest omits entirely. Had the bare root survived, the root list would no longer be
        // conventional and [resources] would have to be authored.
        assertTrue(draft.manifest().build().resources().isEmpty(),
                () -> draft.manifest().build().resources().toString());
        assertTrue(
                draft.notes().stream().anyMatch(note ->
                        note.contains("project-root") && note.contains("resource") && note.contains("dropped")),
                draft.notes().toString());
    }

    @Test
    void conventionalResourceRootIsOmittedWithNoNote() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/resources"));
        Files.createDirectories(tempDir.resolve("src/main/java/com/ex"));
        Files.writeString(tempDir.resolve("src/main/java/com/ex/App.java"), "package com.ex; public class App { }\n");
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <packaging>jar</packaging>
                  <build>
                    <resources>
                      <resource>
                        <directory>src/main/resources</directory>
                        <filtering>false</filtering>
                      </resource>
                    </resources>
                  </build>
                </project>
                """);

        DraftZoltToml draft = mapper.fromMaven(new MavenStaticProjectInspector().inspect(tempDir));

        assertTrue(draft.manifest().build().resources().isEmpty(),
                () -> draft.manifest().build().resources().toString());
        assertTrue(
                draft.notes().stream().noneMatch(note -> note.contains("project-root")),
                draft.notes().toString());
        // A genuine root is carried silently: nothing was dropped and nothing was reported unmappable.
        assertTrue(
                draft.notes().stream().noneMatch(note -> note.contains("resource root")),
                draft.notes().toString());
    }
}
