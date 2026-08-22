package sh.zolt.build.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import sh.zolt.build.ResourceCopyException;
import sh.zolt.project.BuildSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ResourceCopierFailureTest extends ResourceCopierTestSupport {
    private final ResourceCopier copier = new ResourceCopier();

    @Test
    void duplicateConfiguredResourceOutputPathsFailClearly() throws IOException {
        resource("src/main/resources/application.properties", "name=source\n");
        resource("target/generated/resources/application.properties", "name=generated\n");
        BuildSettings settings = buildSettingsWithResourceRoots(
                List.of("src/main/resources", "target/generated/resources"),
                List.of("src/test/resources"));

        ResourceCopyException exception = assertThrows(
                ResourceCopyException.class,
                () -> copier.copyMainResources(projectDir, settings));

        assertEquals(
                "Duplicate resource path `application.properties` from configured resource roots. Remove one copy or choose a distinct output path.",
                exception.getMessage());
    }

    @Test
    void absoluteConfiguredResourceRootsFailClearly() {
        BuildSettings settings = buildSettingsWithResourceRoots(
                List.of(projectDir.resolve("outside").toString()),
                List.of("src/test/resources"));

        ResourceCopyException exception = assertThrows(
                ResourceCopyException.class,
                () -> copier.copyMainResources(projectDir, settings));

        assertTrue(exception.getMessage().contains("[resources].main"));
        assertTrue(exception.getMessage().contains(projectDir.resolve("outside").toString()));
    }

    @Test
    void parentEscapingConfiguredResourceRootsFailClearly() {
        BuildSettings settings = buildSettingsWithResourceRoots(
                List.of("../outside-resources"),
                List.of("src/test/resources"));

        ResourceCopyException exception = assertThrows(
                ResourceCopyException.class,
                () -> copier.copyMainResources(projectDir, settings));

        assertTrue(exception.getMessage().contains("[resources].main"));
        assertTrue(exception.getMessage().contains("../outside-resources"));
    }

    @Test
    void windowsStyleConfiguredResourceRootsFailClearly() {
        BuildSettings settings = buildSettingsWithResourceRoots(
                List.of("C:\\outside\\resources"),
                List.of("src/test/resources"));

        ResourceCopyException exception = assertThrows(
                ResourceCopyException.class,
                () -> copier.copyMainResources(projectDir, settings));

        assertTrue(exception.getMessage().contains("[resources].main"));
        assertTrue(exception.getMessage().contains("C:\\outside\\resources"));
    }

    @Test
    void rejectsResourceSymlinkThatEscapesProject() throws IOException {
        Path outside = Files.createTempFile(projectDir.getParent(), "secret-", ".txt");
        Files.writeString(outside, "secret\n");
        createSymlink(projectDir.resolve("src/main/resources/secret.txt"), outside);

        ResourceCopyException exception = assertThrows(
                ResourceCopyException.class,
                () -> copier.copyMainResources(projectDir, BuildSettings.defaults()));

        assertTrue(exception.getMessage().contains("[resources].main"));
        assertTrue(exception.getMessage().contains("resolved through symlinks"));
    }

    @Test
    void rejectsOutputWithSymlinkedParentBeforeCreatingDirectories() throws IOException {
        resource("src/main/resources/application.properties", "name=demo\n");
        Path outside = Files.createTempDirectory(projectDir.getParent(), "outside-resource-output-");
        createSymlink(projectDir.resolve("target"), outside);

        ResourceCopyException exception = assertThrows(
                ResourceCopyException.class,
                () -> copier.copyMainResources(projectDir, BuildSettings.defaults()));

        assertTrue(exception.getMessage().contains("[build.output].main"));
        assertTrue(exception.getMessage().contains("target/classes"));
        assertTrue(exception.getMessage().contains("resolved through symlinks"));
        assertFalse(Files.exists(outside.resolve("classes")));
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
