package sh.zolt.release.archive;

import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.provenance.BuildProvenanceReader;
import sh.zolt.release.ReleaseTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ReleaseArchiveEnclosingProvenanceTest extends ReleaseArchiveTestSupport {
    private static final String FIRST = "0123456789abcdef0123456789abcdef01234567";
    private static final String SECOND = "89abcdef0123456789abcdef0123456789abcdef";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recordsAndRefreshesEnclosingCheckoutCommitInArchiveAndManifest(boolean linked) throws IOException {
        Path fixture = projectDir;
        Path checkout = fixture.resolve("checkout");
        Files.createDirectories(checkout);
        Path commit;
        if (linked) {
            Path admin = fixture.resolve("admin/worktrees/release");
            Files.createDirectories(admin);
            Files.writeString(checkout.resolve(".git"), "gitdir: ../admin/worktrees/release\n");
            Files.writeString(admin.resolve("commondir"), "../..\n");
            Files.writeString(admin.resolve("HEAD"), "ref: refs/heads/release/1.0.0\n");
            commit = fixture.resolve("admin/refs/heads/release/1.0.0");
            Files.createDirectories(commit.getParent());
            Files.writeString(fixture.resolve("admin/packed-refs"), SECOND + " refs/heads/release/1.0.0\n");
        } else {
            Path git = checkout.resolve(".git");
            Files.createDirectories(git);
            commit = git.resolve("HEAD");
        }
        Files.writeString(commit, FIRST + "\n");
        projectDir = checkout.resolve("apps/zolt");
        Files.createDirectories(projectDir);
        writeProjectFiles();
        Path binary = writeBinary("target/native/zolt");
        BuildProvenanceReader reader = new BuildProvenanceReader();
        ReleaseArchiveService service = new ReleaseArchiveService((directory, environment, clock) ->
                reader.read(directory, "1.0.0", Optional.empty(), environment, clock));

        assertEmbeddedCommit(service, binary, FIRST);
        Files.writeString(commit, SECOND + "\n");
        assertEmbeddedCommit(service, binary, SECOND);
    }

    private void assertEmbeddedCommit(ReleaseArchiveService service, Path binary, String expected) throws IOException {
        ReleaseArchiveResult result = service.assemble(
                projectDir, config(), ReleaseTarget.LINUX_X64, binary, Path.of("dist"));
        String marker = "\"commit\": \"" + expected + "\"";
        String build = tarEntryContent(result.archivePath(), result.rootDirectory() + "/BUILD.json");
        assertTrue(build.contains(marker), build);
        String manifest = Files.readString(result.manifestPath());
        assertTrue(manifest.contains(marker), manifest);
    }
}
