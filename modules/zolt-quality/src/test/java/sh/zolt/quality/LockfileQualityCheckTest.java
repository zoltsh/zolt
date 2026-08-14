package sh.zolt.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LockfileQualityCheckTest extends QualityCheckServiceTestSupport {
    private final LockfileQualityCheck check = new LockfileQualityCheck(
            new ResolveService(),
            new WorkspaceResolveService(),
            new ZoltLockfileReader());

    @TempDir
    private Path tempDir;

    @Test
    void projectLockfileCheckReportsMissingLockfileBeforeResolve() throws IOException {
        Path projectDir = tempDir.resolve("missing-project-lock");
        ProjectConfig config = parseProject(projectDir, "");

        QualityCheckResult result = check.checkProjectLockfile(request(projectDir), config);

        assertResult(
                result,
                QualityCheckService.LOCKFILE,
                QualityCheckStatus.FAILED,
                "zolt.lock",
                "zolt.lock is missing.",
                "Run `zolt resolve`.");
    }

    @Test
    void projectLockfileCheckReportsStaleLockfileWithResolveAction() throws IOException {
        Path projectDir = tempDir.resolve("stale-project-lock");
        ProjectConfig config = parseProject(projectDir, "");
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 6

                [[package]]
                id = "com.example:stale"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "blobs/v2/sha256/%s/stale-1.0.0.jar"
                jarSha256 = "%s"
                dependencies = []
                """.formatted("0".repeat(64), "0".repeat(64)));

        QualityCheckResult result = check.checkProjectLockfile(request(projectDir), config);

        assertEquals(QualityCheckService.LOCKFILE, result.id());
        assertEquals(QualityCheckStatus.FAILED, result.status());
        assertEquals("zolt.lock", result.subject());
        assertTrue(result.message().startsWith("zolt.lock is out of date."));
        assertEquals("Run `zolt resolve`.", result.nextStep());
    }

    @Test
    void projectLockfileOfflineFailureUsesOfflineRetryAction() throws IOException {
        Path projectDir = tempDir.resolve("offline-cache-missing");
        ProjectConfig config = parseProject(projectDir, dependencyBody());
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 6\n");

        QualityCheckResult result = check.checkProjectLockfile(request(projectDir, true, false), config);

        assertEquals(QualityCheckService.LOCKFILE, result.id());
        assertEquals(QualityCheckStatus.FAILED, result.status());
        assertEquals("zolt.lock", result.subject());
        assertTrue(result.message().contains("Offline mode requires cached POM for com.example:offline-missing:1.0.0"));
        assertEquals(
                "Run `zolt resolve` without --offline to seed the cache, then retry `zolt check --check lockfile --offline`.",
                result.nextStep());
    }

    @Test
    void projectLockfileRequireOfflineReadyFailureUsesCiRetryAction() throws IOException {
        Path projectDir = tempDir.resolve("require-offline-ready-missing-cache");
        ProjectConfig config = parseProject(projectDir, dependencyBody());
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 6\n");

        QualityCheckResult result = check.checkProjectLockfile(request(projectDir, false, true), config);

        assertEquals(QualityCheckService.LOCKFILE, result.id());
        assertEquals(QualityCheckStatus.FAILED, result.status());
        assertEquals("zolt.lock", result.subject());
        assertTrue(result.message().contains("Offline mode requires cached POM for com.example:offline-missing:1.0.0"));
        assertEquals(
                "Run `zolt resolve` to seed the cache, then retry `zolt check --context ci --require-offline-ready`.",
                result.nextStep());
    }

    @Test
    void projectLockfilePassesWithOfflineReadyMessageWhenNoArtifactsAreNeeded() throws IOException {
        Path projectDir = tempDir.resolve("lockfile-ready");
        ProjectConfig config = parseProject(projectDir, "");
        new ResolveService().resolve(projectDir, config, tempDir.resolve("cache"));

        QualityCheckResult result = check.checkProjectLockfile(request(projectDir, false, true), config);

        assertResult(
                result,
                QualityCheckService.LOCKFILE,
                QualityCheckStatus.PASSED,
                "zolt.lock",
                "zolt.lock matches zolt.toml and locked artifacts are available from the local cache.",
                "");
    }

    @Test
    void projectCacheIntegrityRequiresLockfile() throws IOException {
        Path projectDir = tempDir.resolve("missing-cache-lock");
        Files.createDirectories(projectDir);

        QualityCheckResult result = check.checkProjectCacheIntegrity(request(projectDir));

        assertResult(
                result,
                QualityCheckService.CACHE_INTEGRITY,
                QualityCheckStatus.FAILED,
                "zolt.lock",
                "zolt.lock is missing.",
                "Run `zolt resolve`.");
    }

    @Test
    void workspaceCacheIntegrityUsesWorkspaceResolveNextStep() throws IOException {
        Path workspaceDir = tempDir.resolve("workspace-missing-lock");
        Files.createDirectories(workspaceDir);

        QualityCheckResult result = check.checkWorkspaceCacheIntegrity(request(workspaceDir), workspace(workspaceDir));

        assertResult(
                result,
                QualityCheckService.CACHE_INTEGRITY,
                QualityCheckStatus.FAILED,
                "zolt.lock",
                "zolt.lock is missing.",
                "Run `zolt resolve --workspace`.");
    }

    @Test
    void workspaceLockfileCheckReportsMissingWorkspaceLockfile() throws IOException {
        Path workspaceDir = tempDir.resolve("workspace-lock-missing");
        Files.createDirectories(workspaceDir);

        QualityCheckResult result = check.checkWorkspaceLockfile(request(workspaceDir), workspace(workspaceDir));

        assertResult(
                result,
                QualityCheckService.LOCKFILE,
                QualityCheckStatus.FAILED,
                "zolt.lock",
                "Workspace zolt.lock is missing.",
                "Run `zolt resolve --workspace`.");
    }

    @Test
    void cacheIntegrityReportsMalformedLockfileWithRefreshNextStep() throws IOException {
        Path projectDir = tempDir.resolve("malformed-lock");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 99
                """);

        QualityCheckResult result = check.checkProjectCacheIntegrity(request(projectDir));

        assertEquals(QualityCheckService.CACHE_INTEGRITY, result.id());
        assertEquals(QualityCheckStatus.FAILED, result.status());
        assertEquals("zolt.lock", result.subject());
        assertTrue(result.message().contains("zolt.lock version 99 is newer than this Zolt supports"));
        assertEquals("Run `zolt resolve` to regenerate zolt.lock.", result.nextStep());
    }

    @Test
    void cacheIntegrityPassesForEmptyReadableLockfile() throws IOException {
        Path projectDir = tempDir.resolve("empty-lock");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");

        QualityCheckResult result = check.checkProjectCacheIntegrity(request(projectDir));

        assertResult(
                result,
                QualityCheckService.CACHE_INTEGRITY,
                QualityCheckStatus.PASSED,
                "zolt.lock",
                "All cached artifacts with lockfile checksums match local bytes.",
                "");
    }

    @Test
    void cacheIntegrityReportsChecksumMismatchWithCacheRefreshNextStep() throws IOException {
        Path projectDir = tempDir.resolve("cache-checksum-mismatch");
        Path cacheRoot = tempDir.resolve("cache");
        String checksum = "0".repeat(64);
        Path jar = cacheRoot.resolve("blobs/v2/sha256/" + checksum + "/mismatch-1.0.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "actual cached bytes\n");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 6

                [[package]]
                id = "com.example:mismatch"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "blobs/v2/sha256/%s/mismatch-1.0.0.jar"
                jarSha256 = "%s"
                dependencies = []
                """.formatted(checksum, checksum));

        QualityCheckResult result = check.checkProjectCacheIntegrity(request(projectDir));

        assertEquals(QualityCheckService.CACHE_INTEGRITY, result.id());
        assertEquals(QualityCheckStatus.FAILED, result.status());
        assertEquals("zolt.lock", result.subject());
        assertTrue(result.message().contains("Cached jar integrity check failed for com.example:mismatch:1.0.0"));
        assertEquals("Remove the cache entry or run `zolt resolve`.", result.nextStep());
    }

    private QualityCheckRequest request(Path projectDir) {
        return request(projectDir, false, false);
    }

    private QualityCheckRequest request(Path projectDir, boolean offline, boolean requireOfflineReady) {
        return new QualityCheckRequest(
                projectDir,
                tempDir.resolve("cache"),
                offline,
                false,
                List.of(),
                QualityCheckContext.CI,
                null,
                null,
                false,
                false,
                requireOfflineReady,
                WorkspaceSelectionRequest.defaults());
    }

    private static String dependencyBody() {
        return """

                [dependencies]
                "com.example:offline-missing" = "1.0.0"
                """;
    }

    private static Workspace workspace(Path root) {
        return new Workspace(
                root,
                root.resolve("zolt.toml"),
                new WorkspaceConfig("workspace", List.of(), List.of(), java.util.Map.of(), java.util.Map.of()),
                List.of());
    }

    private static void assertResult(
            QualityCheckResult result,
            String id,
            QualityCheckStatus status,
            String subject,
            String message,
            String nextStep) {
        assertEquals(id, result.id());
        assertEquals(status, result.status());
        assertEquals(subject, result.subject());
        assertEquals(message, result.message());
        assertEquals(nextStep, result.nextStep());
        assertEquals(Optional.empty(), result.member());
    }
}
