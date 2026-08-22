package sh.zolt.quality.execution;

import sh.zolt.lockfile.ProjectLockfile;
import static sh.zolt.quality.QualityCheckService.EXECUTION_CONTEXT;

import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.quality.QualityCheckContext;
import sh.zolt.quality.QualityCheckResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class ExecutionContextQualityCheck {
    private final ZoltLockfileReader lockfileReader;

    ExecutionContextQualityCheck(ZoltLockfileReader lockfileReader) {
        this.lockfileReader = lockfileReader;
    }

    List<QualityCheckResult> check(
            Optional<String> member,
            Path root,
            QualityCheckContext context) {
        if (context == null) {
            return List.of(QualityCheckResult.passed(
                    EXECUTION_CONTEXT,
                    member,
                    "default",
                    "No execution context policy was requested."));
        }
        if (context == QualityCheckContext.LOCAL) {
            return List.of(QualityCheckResult.passed(
                    EXECUTION_CONTEXT,
                    member,
                    "local",
                    "Local context policy is active. Policy source: built-in local context. Local overlays are allowed, zolt.lock is not required before editing, and CI/release preflights remain explicit."));
        }
        if (context != QualityCheckContext.CI) {
            return List.of(QualityCheckResult.failed(
                    EXECUTION_CONTEXT,
                    member,
                    context.configValue(),
                    "Unsupported execution context `" + context.configValue() + "`.",
                    "Use --context ci for the current Zolt-owned context policy."));
        }
        Path lockfile = ProjectLockfile.in(root);
        if (!Files.isRegularFile(lockfile)) {
            return List.of(QualityCheckResult.failed(
                    EXECUTION_CONTEXT,
                    member,
                    "ci",
                    "CI context requires zolt.lock before build work starts.",
                    "Run `zolt resolve`, commit zolt.lock, then rerun `zolt check --context ci`."));
        }
        try {
            ZoltLockfile parsed = lockfileReader.read(lockfile);
            Optional<LockPackage> localOverlay = parsed.packages().stream()
                    .filter(lockPackage -> lockPackage.source().startsWith("local-overlay:"))
                    .findFirst();
            if (localOverlay.isPresent()) {
                LockPackage lockPackage = localOverlay.orElseThrow();
                return List.of(QualityCheckResult.failed(
                        EXECUTION_CONTEXT,
                        member,
                        coordinate(lockPackage),
                        "CI context rejects local repository overlay origin `" + lockPackage.source() + "`.",
                        "Run `zolt resolve --locked --no-local-overlays` or refresh zolt.lock without local overlays."));
            }
        } catch (LockfileReadException exception) {
            return List.of(QualityCheckResult.failed(
                    EXECUTION_CONTEXT,
                    member,
                    "zolt.lock",
                    exception.getMessage(),
                    "Run `zolt resolve`, commit zolt.lock, then rerun `zolt check --context ci`."));
        }
        return List.of(QualityCheckResult.passed(
                EXECUTION_CONTEXT,
                member,
                "ci",
                "CI context policy is active. Policy source: built-in ci context. Locked model checks, generated-source checks, package diagnostics, local overlay rejection, and credential preflight are enabled."));
    }

    private static String coordinate(LockPackage lockPackage) {
        return lockPackage.packageId() + ":" + lockPackage.version();
    }
}
