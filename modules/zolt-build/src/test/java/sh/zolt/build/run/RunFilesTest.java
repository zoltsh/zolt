package sh.zolt.build.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunFilesTest {
    @TempDir
    private Path directory;

    @Test
    void projectAliasStillCapturesCanonicalLocalRuntimeDependencies() throws Exception {
        Path project = directory.resolve("project");
        Files.createDirectories(project);
        Path alias = directory.resolve("alias");
        try {
            Files.createSymbolicLink(alias, project);
        } catch (IOException | UnsupportedOperationException exception) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links are unavailable: " + exception);
        }
        Path dependency = project.resolve("runtime.jar");
        Files.writeString(dependency, "runtime bytes");
        Path snapshot;
        try (RunFiles files = RunFiles.create(alias)) {
            files.captureProjectEntries(alias, List.of(dependency));
            snapshot = files.root();
            Path captured = files.remap(dependency);
            assertTrue(captured.startsWith(snapshot));
            Files.delete(dependency);
            assertEquals("runtime bytes", Files.readString(captured));
        }
        assertFalse(Files.exists(snapshot));
    }
}
