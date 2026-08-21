package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.explain.gradle.GradleStaticProjectInspector;
import sh.zolt.explain.maven.MavenStaticProjectInspector;
import sh.zolt.manifest.authored.AuthoredManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JavaVersionNotationEmitTest {
    @TempDir
    private Path tempDir;

    private final InspectionToManifest mapper = new InspectionToManifest();

    @Test
    void mavenDraftNormalizesLegacyJavaFeatureNotation() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>legacy-java</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.source>1.8</maven.compiler.source>
                    <maven.compiler.target>1.8</maven.compiler.target>
                  </properties>
                </project>
                """);

        DraftZoltToml draft = mapper.fromMaven(new MavenStaticProjectInspector().inspect(tempDir));
        String rendered = render(draft);

        assertEquals(Optional.of(8), DraftManifestSubject.of(draft).javaRelease());
        assertTrue(hasLine(rendered, "java = 8"), () -> rendered);
        assertFalse(hasLine(rendered, "# java = 8"), () -> rendered);
    }

    @Test
    void gradleDraftNormalizesUnquotedLegacyJavaFeatureNotation() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'legacy-gradle'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins { id 'java' }
                sourceCompatibility = 1.8
                targetCompatibility = 1.8
                """);

        DraftZoltToml draft = mapper.fromGradle(new GradleStaticProjectInspector().inspect(tempDir));

        assertEquals(Optional.of(8), DraftManifestSubject.of(draft).javaRelease());
    }

    @Test
    void gradleDraftNormalizesJavaVersionEnumLegacyNotation() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'legacy-gradle'\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins { id 'java' }
                sourceCompatibility = JavaVersion.VERSION_1_8
                """);

        DraftZoltToml draft = mapper.fromGradle(new GradleStaticProjectInspector().inspect(tempDir));

        assertEquals(Optional.of(8), DraftManifestSubject.of(draft).javaRelease());
    }

    @Test
    void mavenDraftOmitsUnknownJavaFromExecutionScopedCompilerConfig() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>execution-java</artifactId>
                  <version>1.0.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <version>3.13.0</version>
                        <executions>
                          <execution>
                            <goals>
                              <goal>compile</goal>
                            </goals>
                            <configuration>
                              <source>8</source>
                              <target>8</target>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        DraftZoltToml draft = mapper.fromMaven(new MavenStaticProjectInspector().inspect(tempDir));
        String rendered = render(draft);

        assertUnreadableJavaIsOmittedWithNote(draft, rendered);
    }

    @Test
    void gradleDraftOmitsUnknownJavaWithoutDetectableToolchain() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'unknown-gradle'\n");
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }\n");

        DraftZoltToml draft = mapper.fromGradle(new GradleStaticProjectInspector().inspect(tempDir));
        String rendered = render(draft);

        assertUnreadableJavaIsOmittedWithNote(draft, rendered);
    }

    /**
     * {@code [project].java} is an integer in the final language, so an unreadable feature version
     * cannot be drafted at all — not even as a commented-out line. The draft omits the key and keeps
     * the audited notation in the review note instead.
     */
    private static void assertUnreadableJavaIsOmittedWithNote(DraftZoltToml draft, String rendered) {
        assertTrue(DraftManifestSubject.of(draft).javaRelease().isEmpty(), () -> rendered);
        assertTrue(rendered.contains("# Review items:"), () -> rendered);
        assertTrue(
                draft.notes().stream().anyMatch(note ->
                        note.contains("Project Java version could not be determined")
                                && note.contains("`unknown`")
                                && note.contains("add `[project].java`")),
                () -> draft.notes().toString());
        assertFalse(rendered.lines().anyMatch(line -> line.strip().startsWith("java =")), () -> rendered);
        assertFalse(rendered.contains("# java ="), () -> rendered);
    }

    private static String render(DraftZoltToml draft) {
        return new DraftZoltTomlRenderer().render(draft, JavaVersionNotationEmitTest::projectOnlyToml);
    }

    /** A sparse {@code [project]} table: absent identity values are not materialized. */
    private static String projectOnlyToml(AuthoredManifest manifest) {
        DraftManifestSubject subject = DraftManifestSubject.of(manifest);
        StringBuilder out = new StringBuilder("[project]\n");
        out.append("name = \"").append(subject.name()).append("\"\n");
        subject.version().ifPresent(value -> out.append("version = \"").append(value).append("\"\n"));
        subject.group().ifPresent(value -> out.append("group = \"").append(value).append("\"\n"));
        subject.javaRelease().ifPresent(value -> out.append("java = ").append(value).append('\n'));
        return out.append('\n').toString();
    }

    private static boolean hasLine(String rendered, String expected) {
        return rendered.lines().anyMatch(expected::equals);
    }
}
