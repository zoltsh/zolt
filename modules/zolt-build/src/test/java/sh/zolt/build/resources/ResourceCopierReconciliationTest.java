package sh.zolt.build.resources;

import static org.junit.jupiter.api.Assertions.*;

import sh.zolt.project.BuildSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import sh.zolt.build.ResourceCopyException;
import sh.zolt.build.packageplan.PackageInputFingerprinting;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

final class ResourceCopierReconciliationTest extends ResourceCopierTestSupport {
    private final ResourceCopier copier = new ResourceCopier();

    @Test
    void removesDeletedMainResourcesWithoutDeletingCompilerOutputs() throws IOException {
        Path resource = resource("src/main/resources/application.properties", "before=true\n");
        copier.copyMainResources(projectDir, BuildSettings.defaults());
        Path compiled = resource("target/classes/example/Main.class", "compiled");
        Files.delete(resource);

        copier.copyMainResources(projectDir, BuildSettings.defaults());

        assertFalse(Files.exists(projectDir.resolve("target/classes/application.properties")));
        assertEquals("compiled", Files.readString(compiled));
    }

    @Test
    void removesRenamedTestResources() throws IOException {
        Path old = resource("src/test/resources/old.txt", "fixture");
        copier.copyTestResources(projectDir, BuildSettings.defaults());
        Files.move(old, old.resolveSibling("new.txt"));

        copier.copyTestResources(projectDir, BuildSettings.defaults());

        assertFalse(Files.exists(projectDir.resolve("target/test-classes/old.txt")));
        assertEquals("fixture", Files.readString(projectDir.resolve("target/test-classes/new.txt")));
    }

    @Test
    void supportsResourceFileBecomingDirectoryAndBack() throws IOException {
        Path file = resource("src/main/resources/config", "old");
        copier.copyMainResources(projectDir, BuildSettings.defaults());
        Files.delete(file);
        Path nested = resource("src/main/resources/config/app.txt", "nested");
        copier.copyMainResources(projectDir, BuildSettings.defaults());
        assertEquals("nested", Files.readString(projectDir.resolve("target/classes/config/app.txt")));
        Files.delete(nested);
        Files.delete(nested.getParent());
        resource("src/main/resources/config", "new");

        copier.copyMainResources(projectDir, BuildSettings.defaults());

        assertEquals("new", Files.readString(projectDir.resolve("target/classes/config")));
    }
    @Test
    void removedResourceRootsAreReconciled() throws IOException {
        resource("src/main/resources/old.txt", "old");
        copier.copyMainResources(projectDir, BuildSettings.defaults());

        copier.copyMainResources(projectDir, buildSettingsWithResourceRoots(List.of(), List.of()));

        assertFalse(Files.exists(projectDir.resolve("target/classes/old.txt")));
    }

    @Test
    void inventoryIsExcludedFromApplicationArchives() throws IOException {
        resource("src/main/resources/app.txt", "app");
        copier.copyMainResources(projectDir, BuildSettings.defaults());
        Path output = projectDir.resolve("target/classes");

        assertEquals(List.of(output.resolve("app.txt")), PackageInputFingerprinting.applicationFiles(output));
    }

    @Test
    void rejectsEscapingInventoryBeforeDeletingAnyFile() throws IOException {
        Path source = resource("src/main/resources/keep.txt", "keep");
        Path state = resource("target/classes/.zolt-resources.state", "zolt-resources-v1\n"
                + Base64.getEncoder().encodeToString("../../src/main/resources/keep.txt".getBytes(StandardCharsets.UTF_8))
                + "\n");

        assertThrows(ResourceCopyException.class,
                () -> copier.copyMainResources(projectDir, BuildSettings.defaults()));

        assertEquals("keep", Files.readString(source));
        assertTrue(Files.exists(state));
    }

    @Test
    void rejectsOutputSymlinkInsideProjectWithoutChangingItsTarget() throws IOException {
        resource("src/main/resources/link/keep.txt", "copied");
        Path authored = resource("authored/keep.txt", "authored");
        Files.createDirectories(projectDir.resolve("target/classes"));
        Files.createSymbolicLink(projectDir.resolve("target/classes/link"), authored.getParent());

        assertThrows(ResourceCopyException.class,
                () -> copier.copyMainResources(projectDir, BuildSettings.defaults()));

        assertEquals("authored", Files.readString(authored));
    }

}
