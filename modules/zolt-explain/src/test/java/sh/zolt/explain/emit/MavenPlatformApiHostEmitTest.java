package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.explain.maven.MavenInspectionResult;
import sh.zolt.explain.maven.MavenStaticProjectInspector;
import sh.zolt.manifest.authored.AuthoredManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MavenPlatformApiHostEmitTest {
    @TempDir
    private Path tempDir;

    private final InspectionToManifest mapper = new InspectionToManifest();
    private final DraftZoltTomlRenderer renderer = new DraftZoltTomlRenderer();

    @Test
    void sourceTargetBelowJdkEmitsStrictReleaseWithCommentedHostSuggestion() throws IOException {
        DraftZoltToml draft = draftFor("""
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>legacy</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.source>8</maven.compiler.source>
                    <maven.compiler.target>8</maven.compiler.target>
                  </properties>
                </project>
                """);

        assertTrue(draft.suggestCompilerJdkApiHost());
        assertTrue(draft.notes().stream().anyMatch(note ->
                note.contains("source/target 8 below the build JDK")
                        && note.contains("forfeits cross-JDK reproducibility")),
                () -> draft.notes().toString());

        String rendered = renderer.render(draft, new FakeManifestRenderer());
        assertTrue(rendered.contains("[compiler]"), rendered);
        assertTrue(rendered.contains("# jdkApi = \"host\""), rendered);
        // The live value stays strict: no uncommented jdkApi assignment.
        assertFalse(rendered.contains("\njdkApi = \"host\""), rendered);
    }

    @Test
    void releasePomEmitsNoHostSuggestion() throws IOException {
        DraftZoltToml draft = draftFor("""
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>strict</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>8</maven.compiler.release>
                  </properties>
                </project>
                """);

        assertFalse(draft.suggestCompilerJdkApiHost());
        assertFalse(draft.notes().stream().anyMatch(note -> note.contains("jdkApi")),
                () -> draft.notes().toString());

        String rendered = renderer.render(draft, new FakeManifestRenderer());
        assertFalse(rendered.contains("jdkApi"), rendered);
    }

    @Test
    void renderedHostSuggestionIsDeterministic() throws IOException {
        DraftZoltToml draft = draftFor("""
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>legacy</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.source>8</maven.compiler.source>
                    <maven.compiler.target>8</maven.compiler.target>
                  </properties>
                </project>
                """);

        String first = renderer.render(draft, new FakeManifestRenderer());
        String second = renderer.render(draft, new FakeManifestRenderer());
        assertTrue(first.equals(second), first);
    }

    private DraftZoltToml draftFor(String pom) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), pom);
        MavenInspectionResult result = new MavenStaticProjectInspector().inspect(tempDir);
        return mapper.fromMaven(result);
    }

    /** A sparse {@code [project]} table: absent identity values are not materialized. */
    private static final class FakeManifestRenderer implements AuthoredManifestRenderer {
        @Override
        public String render(AuthoredManifest manifest) {
            DraftManifestSubject subject = DraftManifestSubject.of(manifest);
            StringBuilder out = new StringBuilder("[project]\n");
            out.append("name = \"").append(subject.name()).append("\"\n");
            subject.version().ifPresent(value -> out.append("version = \"").append(value).append("\"\n"));
            subject.group().ifPresent(value -> out.append("group = \"").append(value).append("\"\n"));
            subject.javaRelease().ifPresent(value -> out.append("java = ").append(value).append('\n'));
            return out.toString();
        }
    }
}
