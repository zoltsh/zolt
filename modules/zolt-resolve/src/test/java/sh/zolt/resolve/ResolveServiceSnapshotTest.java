package sh.zolt.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.materialization.RepositoryOverlay;
import sh.zolt.resolve.support.ResolveServiceTestSupport;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ResolveServiceSnapshotTest extends ResolveServiceTestSupport {
    private static final PackageId SNAP = new PackageId("com.example", "snap");

    @Test
    void overlayPresentSnapshotResolvesFromOverlayAndRecordsPinnedOrigin() {
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        Path mavenLocalRoot = tempDir.resolve("m2/repository");
        createDirectory(projectDir);
        writeLocalArtifact(
                mavenLocalRoot,
                "com.example",
                "snap",
                "1.0.0-SNAPSHOT",
                simplePom("com.example", "snap", "1.0.0-SNAPSHOT"),
                Map.of("snap.txt", "local snapshot\n"));

        ResolveResult result = resolveService.resolve(
                projectDir,
                snapshotConfig(),
                cacheRoot,
                false,
                new ResolveOptions(false, List.of(RepositoryOverlay.mavenLocal(mavenLocalRoot)), false));

        assertEquals(1, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        LockPackage snap = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(SNAP))
                .findFirst()
                .orElseThrow();
        assertEquals("1.0.0-SNAPSHOT", snap.version());
        assertEquals("local-overlay:maven-local", snap.source());
        assertTrue(snap.jar().orElseThrow().startsWith("blobs/v2/sha256/"), snap.jar().toString());
        assertTrue(snap.jar().orElseThrow().endsWith("/snap-1.0.0-SNAPSHOT.jar"));
        assertTrue(snap.jarSha256().isPresent(), "overlay snapshot jar must be SHA-256 pinned");
        // Never falls through to remote for a SNAPSHOT.
        assertEquals(0, requestCount(pomRepositoryPath("com.example", "snap", "1.0.0-SNAPSHOT")));
        assertEquals(0, requestCount(jarRepositoryPath("com.example", "snap", "1.0.0-SNAPSHOT")));
    }

    @Test
    void overlayEnabledButSnapshotAbsentRejectsWithSupportedSubsetAndNeverContactsRemote() {
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        Path emptyOverlay = tempDir.resolve("empty-m2/repository");
        createDirectory(projectDir);

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(
                        projectDir,
                        snapshotConfig(),
                        cacheRoot,
                        false,
                        new ResolveOptions(false, List.of(RepositoryOverlay.mavenLocal(emptyOverlay)), false)));

        assertTrue(exception.getMessage().contains("Unsupported SNAPSHOT dependency version `1.0.0-SNAPSHOT`"),
                exception.getMessage());
        assertTrue(exception.getMessage().contains("workspace members and maven-local overlay artifacts"),
                exception.getMessage());
        assertTrue(exception.getMessage().contains("remote SNAPSHOT feeds are unsupported by design"),
                exception.getMessage());
        assertTrue(exception.getMessage().contains("enabled maven-local overlay"), exception.getMessage());
        assertEquals(0, totalRequests.get());
    }

    @Test
    void snapshotWithoutOverlayRejectsAndNamesOverlayFlag() {
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(projectDir, snapshotConfig(), cacheRoot));

        assertTrue(exception.getMessage().contains("Unsupported SNAPSHOT dependency version `1.0.0-SNAPSHOT`"),
                exception.getMessage());
        assertTrue(exception.getMessage().contains("remote SNAPSHOT feeds are unsupported by design"),
                exception.getMessage());
        assertTrue(exception.getMessage().contains("--repository-overlay maven-local"), exception.getMessage());
        assertEquals(0, totalRequests.get());
    }

    private ProjectConfig snapshotConfig() {
        return configWithDependencies(Map.of("com.example:snap", "1.0.0-SNAPSHOT"));
    }
}
