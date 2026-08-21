package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.resolve.ResolveResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class WorkspaceResolveServiceSnapshotTest extends WorkspaceResolveServiceTestSupport {
    @Test
    void workspaceMemberSnapshotResolvesLocksAndRoundTripsDeterministically() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["apps/api", "modules/core"]

                [repositories]
                central = false

                [repositories.test]
                url = "%s"
                """.formatted(baseUri));
        member("apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = true }
                """);
        snapshotMember("modules/core", "core", "0.1.0-SNAPSHOT");

        ResolveResult result = service.resolve(tempDir, tempDir.resolve("cache"), false, false);

        Path lockfilePath = tempDir.resolve("zolt.lock");
        assertEquals(lockfilePath, result.lockfilePath());
        ZoltLockfile lockfile = lockfileReader.read(lockfilePath);
        LockPackage core = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId("com.acme", "core")))
                .findFirst()
                .orElseThrow();
        assertEquals("0.1.0-SNAPSHOT", core.version());
        assertEquals("workspace", core.source());
        assertEquals("modules/core", core.workspace().orElseThrow());

        // Deterministic round-trip: a locked resolve must not report drift, and the lock must be byte-identical.
        String firstLock = Files.readString(lockfilePath);
        service.resolve(tempDir, tempDir.resolve("cache"), true, false);
        assertEquals(firstLock, Files.readString(lockfilePath));
        assertTrue(Files.readString(lockfilePath).contains("0.1.0-SNAPSHOT"));
    }

    private void snapshotMember(String path, String name, String version) throws IOException {
        Path member = tempDir.resolve(path);
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "%s"
                group = "com.acme"
                java = 21
                """.formatted(name, version));
    }
}
