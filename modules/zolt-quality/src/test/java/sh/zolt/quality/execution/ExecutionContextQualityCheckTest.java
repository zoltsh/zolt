package sh.zolt.quality.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.quality.QualityCheckContext;
import sh.zolt.quality.QualityCheckResult;
import sh.zolt.quality.QualityCheckService;
import sh.zolt.quality.QualityCheckStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ExecutionContextQualityCheckTest {
    private final ExecutionContextQualityCheck check = new ExecutionContextQualityCheck(new ZoltLockfileReader());

    @TempDir
    private Path tempDir;

    @Test
    void defaultContextReportsNoPolicyRequested() {
        QualityCheckResult result = check.check(Optional.empty(), tempDir, null).getFirst();

        assertResult(
                result,
                QualityCheckStatus.PASSED,
                "default",
                "No execution context policy was requested.",
                "");
    }

    @Test
    void localContextExplainsRelaxedPolicy() {
        QualityCheckResult result = check.check(Optional.of("modules/api"), tempDir, QualityCheckContext.LOCAL).getFirst();

        assertEquals(Optional.of("modules/api"), result.member());
        assertResult(
                result,
                QualityCheckStatus.PASSED,
                "local",
                "Local context policy is active. Policy source: built-in local context. Local overlays are allowed, zolt.lock is not required before editing, and CI/release preflights remain explicit.",
                "");
    }

    @Test
    void ciContextRequiresLockfile() {
        QualityCheckResult result = check.check(Optional.empty(), tempDir, QualityCheckContext.CI).getFirst();

        assertResult(
                result,
                QualityCheckStatus.FAILED,
                "ci",
                "CI context requires zolt.lock before build work starts.",
                "Run `zolt resolve`, commit zolt.lock, then rerun `zolt check --context ci`.");
    }

    @Test
    void ciContextReportsMalformedLockfileWithCommitNextStep() throws IOException {
        Files.writeString(tempDir.resolve("zolt.lock"), "version = 99\n");

        QualityCheckResult result = check.check(Optional.empty(), tempDir, QualityCheckContext.CI).getFirst();

        assertEquals(QualityCheckService.EXECUTION_CONTEXT, result.id());
        assertEquals(QualityCheckStatus.FAILED, result.status());
        assertEquals("zolt.lock", result.subject());
        org.junit.jupiter.api.Assertions.assertTrue(result.message().contains(
                "zolt.lock version 99 is newer than this Zolt supports"));
        assertEquals("Run `zolt resolve`, commit zolt.lock, then rerun `zolt check --context ci`.", result.nextStep());
    }

    @Test
    void ciContextRejectsLocalOverlayOrigins() throws IOException {
        Files.writeString(tempDir.resolve("zolt.lock"), """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:local-lib"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.example:local-lib"
                version = "1.0.0"
                source = "local-overlay:maven-local"
                scope = "compile"
                direct = true
                jar = "overlays/maven-local/com/example/local-lib/1.0.0/local-lib-1.0.0.jar"
                dependencies = []
                """);

        QualityCheckResult result = check.check(Optional.empty(), tempDir, QualityCheckContext.CI).getFirst();

        assertResult(
                result,
                QualityCheckStatus.FAILED,
                "com.example:local-lib:1.0.0",
                "CI context rejects local repository overlay origin `local-overlay:maven-local`.",
                "Run `zolt resolve --locked --no-local-overlays` or refresh zolt.lock without local overlays.");
    }

    @Test
    void ciContextPassesForReadableLockfileWithoutLocalOverlays() throws IOException {
        Files.writeString(tempDir.resolve("zolt.lock"), "version = 7\n");

        QualityCheckResult result = check.check(Optional.empty(), tempDir, QualityCheckContext.CI).getFirst();

        assertResult(
                result,
                QualityCheckStatus.PASSED,
                "ci",
                "CI context policy is active. Policy source: built-in ci context. Locked model checks, generated-source checks, package diagnostics, local overlay rejection, and credential preflight are enabled.",
                "");
    }

    private static void assertResult(
            QualityCheckResult result,
            QualityCheckStatus status,
            String subject,
            String message,
            String nextStep) {
        assertEquals(QualityCheckService.EXECUTION_CONTEXT, result.id());
        assertEquals(status, result.status());
        assertEquals(subject, result.subject());
        assertEquals(message, result.message());
        assertEquals(nextStep, result.nextStep());
    }
}
