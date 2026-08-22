package sh.zolt.workspace.discovery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildException;
import sh.zolt.workspace.service.WorkspaceInputs;

final class WorkspaceDiscoveryDirectorySnapshotTest {
    private final ManifestWorkspaceDiscovery discovery = new ManifestWorkspaceDiscovery();

    @TempDir
    private Path tempDir;

    @Test
    void unchangedDirectoryEvidencePassesCurrentInputCheck() throws IOException {
        DiscoveredWorkspace workspace = workspaceWithNonProjectCandidate();

        assertDoesNotThrow(workspace.inputs()::requireCurrent);
    }

    @Test
    void newlyCreatedMatchingDirectoryInvalidatesCapturedInputs() throws IOException {
        DiscoveredWorkspace workspace = workspaceWithNonProjectCandidate();

        Files.createDirectory(tempDir.resolve("apps/new-member"));

        assertThrows(BuildException.class, workspace.inputs()::requireCurrent);
    }

    @Test
    void deletedCandidateDirectoryInvalidatesCapturedInputs() throws IOException {
        DiscoveredWorkspace workspace = workspaceWithNonProjectCandidate();

        Files.delete(tempDir.resolve("apps/docs"));

        assertThrows(BuildException.class, workspace.inputs()::requireCurrent);
    }

    @Test
    void candidateEntryTypeChangeInvalidatesCapturedInputs() throws IOException {
        DiscoveredWorkspace workspace = workspaceWithNonProjectCandidate();
        Path docs = tempDir.resolve("apps/docs");

        Files.delete(docs);
        Files.writeString(docs, "now a file\n");

        assertThrows(BuildException.class, workspace.inputs()::requireCurrent);
    }

    @Test
    void traversedDirectoryReplacedBySymlinkInvalidatesCapturedInputs() throws IOException {
        DiscoveredWorkspace workspace = workspaceWithNonProjectCandidate();
        Path apps = tempDir.resolve("apps");
        Path relocated = tempDir.resolve("relocated-apps");
        Files.move(apps, relocated);
        try {
            Files.createSymbolicLink(apps, relocated);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.abort("Symbolic links unavailable: " + exception.getMessage());
        }

        assertThrows(BuildException.class, workspace.inputs()::requireCurrent);
    }

    @Test
    void directoryEvidenceDoesNotEnterSemanticInputDigests() throws IOException {
        DiscoveredWorkspace workspace = workspaceWithNonProjectCandidate();

        assertEquals(
                Set.of("zolt.toml", "apps/api/zolt.toml", "apps/docs/zolt.toml"),
                workspace.inputs().digestsRelativeTo(tempDir).keySet());
        assertFalse(workspace.inputs().digestsRelativeTo(tempDir).containsKey("apps"));
    }

    @Test
    void derivedFileSnapshotsRetainDirectoryEvidence() throws IOException {
        Path lockfile = tempDir.resolve("zolt.lock");
        byte[] content = "lock\n".getBytes(StandardCharsets.UTF_8);
        Files.write(lockfile, content);
        DiscoveredWorkspace workspace = workspaceWithNonProjectCandidate();
        WorkspaceInputs derived = workspace.inputs().withContent(lockfile, content);
        assertDoesNotThrow(derived::requireCurrent);

        Files.createDirectory(tempDir.resolve("apps/new-member"));

        assertThrows(BuildException.class, derived::requireCurrent);
    }

    private DiscoveredWorkspace workspaceWithNonProjectCandidate() throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]

                [workspace.project]
                group = "com.example"
                version = "1.0.0"
                java = 21
                """);
        Path api = tempDir.resolve("apps/api");
        Files.createDirectories(api);
        Files.writeString(api.resolve("zolt.toml"), """
                [project]
                name = "api"
                """);
        Files.createDirectory(tempDir.resolve("apps/docs"));
        return discovery.load(tempDir);
    }
}
