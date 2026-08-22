package sh.zolt.build.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import sh.zolt.build.SourceDiscoveryException;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SourceDiscovererDiagnosticsTest {
    private final SourceDiscoverer discoverer = new SourceDiscoverer();

    @TempDir
    private Path projectDir;

    @Test
    void failsWhenRequiredDeclaredGeneratedRootIsMissing() throws IOException {
        source("src/main/openapi/api.yaml");

        SourceDiscoveryException exception = assertThrows(
                SourceDiscoveryException.class,
                () -> discoverer.discover(
                        projectDir,
                        BuildSettings.defaults().withGeneratedSources(
                                List.of(new GeneratedSourceStep(
                                        "openapi",
                                        GeneratedSourceKind.DECLARED_ROOT,
                                        "java",
                                        "target/generated/sources/openapi",
                                        List.of("src/main/openapi/api.yaml"),
                                        true,
                                        false)),
                                List.of())));

        assertTrue(exception.getMessage().contains("Generated source root `target/generated/sources/openapi` is missing"));
    }

    @Test
    void ignoresOptionalMissingDeclaredGeneratedRoot() throws IOException {
        source("src/main/openapi/api.yaml");

        SourceDiscoveryResult result = discoverer.discover(
                projectDir,
                BuildSettings.defaults().withGeneratedSources(
                        List.of(new GeneratedSourceStep(
                                "openapi",
                                GeneratedSourceKind.DECLARED_ROOT,
                                "java",
                                "target/generated/sources/openapi",
                                List.of("src/main/openapi/api.yaml"),
                                false,
                                false)),
                        List.of()));

        assertEquals(List.of(), result.mainSources());
    }

    @Test
    void rejectsGeneratedSourcePathsOutsideProject() {
        SourceDiscoveryException exception = assertThrows(
                SourceDiscoveryException.class,
                () -> discoverer.discover(
                        projectDir,
                        BuildSettings.defaults().withGeneratedSources(
                                List.of(new GeneratedSourceStep(
                                        "openapi",
                                        GeneratedSourceKind.DECLARED_ROOT,
                                        "java",
                                        "../outside",
                                        List.of("src/main/openapi/api.yaml"),
                                        true,
                                        false)),
                                List.of())));

        assertTrue(exception.getMessage().contains("Invalid generated source output path"));
    }

    @Test
    void rejectsMainSourceRootOutsideProject() {
        SourceDiscoveryException exception = assertThrows(
                SourceDiscoveryException.class,
                () -> discoverer.discover(
                        projectDir,
                        new BuildSettings("../outside", "src/test/java", "target/classes", "target/test-classes")));

        assertTrue(exception.getMessage().contains("[build].sources"));
        assertTrue(exception.getMessage().contains("../outside"));
    }

    @Test
    void rejectsWindowsStyleTestSourceRoot() {
        SourceDiscoveryException exception = assertThrows(
                SourceDiscoveryException.class,
                () -> discoverer.discover(
                        projectDir,
                        new BuildSettings(
                                "src/main/java",
                                "src/test/java",
                                "target/classes",
                                "target/test-classes",
                                List.of("C:\\outside\\tests"))));

        assertTrue(exception.getMessage().contains("[test.sources].java"));
        assertTrue(exception.getMessage().contains("C:\\outside\\tests"));
    }

    @Test
    void rejectsSourceSymlinkThatEscapesProject() throws IOException {
        Path outside = Files.createTempFile(projectDir.getParent(), "Outside-", ".java");
        Files.writeString(outside, "final class Outside {}\n");
        createSymlink(projectDir.resolve("src/main/java/com/example/Outside.java"), outside);

        SourceDiscoveryException exception = assertThrows(
                SourceDiscoveryException.class,
                () -> discoverer.discover(projectDir, BuildSettings.defaults()));

        assertTrue(exception.getMessage().contains("[build].sources"));
        assertTrue(exception.getMessage().contains("resolved through symlinks"));
    }

    private Path source(String path) throws IOException {
        Path source = projectDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, "final class Example {}\n");
        return source.normalize();
    }

    private static void createSymlink(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }
    }
}
