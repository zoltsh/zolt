package sh.zolt.cli.command.dependency;

import sh.zolt.error.ActionableError;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.manifest.ZoltManifestDocument;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Failure-safe manifest and lockfile edit transaction shared by every public mutation command.
 *
 * <p>The mutation is expressed against the authored final-language model and committed through the
 * source-safe editor, so unrelated bytes — including domains the build engine never models — survive
 * byte for byte, and a request the editor cannot represent fails before any file is touched
 * (design §19).
 */
final class ManifestEditTransaction {
    private ManifestEditTransaction() {
    }

    static ManifestEditResult execute(
            Path projectRoot,
            Path cacheRoot,
            boolean noResolve,
            ManifestMutationServices manifests,
            ResolveService resolveService,
            UnaryOperator<AuthoredManifest> mutation) {
        return execute(
                projectRoot, cacheRoot, noResolve, manifests, resolveService, null, mutation, () -> {});
    }

    static ManifestEditResult execute(
            Path projectRoot,
            Path cacheRoot,
            boolean noResolve,
            ManifestMutationServices manifests,
            ResolveService resolveService,
            ScopeExpectation expectation,
            UnaryOperator<AuthoredManifest> mutation) {
        return execute(
                projectRoot, cacheRoot, noResolve, manifests, resolveService, expectation, mutation, () -> {});
    }

    /** Test seam for a deterministic writer arriving after staging but before the lockfile CAS. */
    static ManifestEditResult execute(
            Path projectRoot,
            Path cacheRoot,
            boolean noResolve,
            ManifestMutationServices manifests,
            ResolveService resolveService,
            UnaryOperator<AuthoredManifest> mutation,
            Runnable beforeLockfileCompareAndSet) {
        return execute(
                projectRoot,
                cacheRoot,
                noResolve,
                manifests,
                resolveService,
                null,
                mutation,
                beforeLockfileCompareAndSet);
    }

    static ManifestEditResult execute(
            Path projectRoot,
            Path cacheRoot,
            boolean noResolve,
            ManifestMutationServices manifests,
            ResolveService resolveService,
            ScopeExpectation expectation,
            UnaryOperator<AuthoredManifest> mutation,
            Runnable beforeLockfileCompareAndSet) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(manifests, "manifests");
        Objects.requireNonNull(mutation, "mutation");
        Objects.requireNonNull(beforeLockfileCompareAndSet, "beforeLockfileCompareAndSet");
        return ManifestMutationLockGuard.withLock(projectRoot, lockRoot -> {
            ManifestEditRecovery.recoverAll(projectRoot, lockRoot);
            ManifestMutationScope scope = ManifestMutationScope.discover(projectRoot, lockRoot);
            requireExpectedScope(scope, expectation);
            return commit(
                    scope, cacheRoot, noResolve, manifests, resolveService, mutation,
                    beforeLockfileCompareAndSet);
        });
    }

    /** Edits the workspace-root manifest itself, whose lockfile is the same authoritative root lock. */
    static ManifestEditResult executeWorkspaceRoot(
            Path workspaceRoot,
            Path cacheRoot,
            boolean noResolve,
            ManifestMutationServices manifests,
            ResolveService resolveService,
            ScopeExpectation expectation,
            UnaryOperator<AuthoredManifest> mutation) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(manifests, "manifests");
        Objects.requireNonNull(expectation, "expectation");
        Objects.requireNonNull(mutation, "mutation");
        return ManifestMutationLockGuard.withLock(workspaceRoot, lockRoot -> {
            ManifestEditRecovery.recoverAll(workspaceRoot, lockRoot);
            ManifestMutationScope scope = ManifestMutationScope.discoverWorkspaceRoot(
                    workspaceRoot, lockRoot, expectation.manifestPath());
            requireExpectedScope(scope, expectation);
            return commit(scope, cacheRoot, noResolve, manifests, resolveService, mutation, () -> {});
        });
    }

    /** Recovers pending edits and performs a read-only decision under the mutation lock. */
    static <T> T inspectLocked(Path projectRoot, Function<Path, T> inspection) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(inspection, "inspection");
        return ManifestMutationLockGuard.withLock(projectRoot, lockRoot -> {
            ManifestEditRecovery.recoverAll(projectRoot, lockRoot);
            return inspection.apply(lockRoot);
        });
    }

    private static ManifestEditResult commit(
            ManifestMutationScope scope,
            Path cacheRoot,
            boolean noResolve,
            ManifestMutationServices manifests,
            ResolveService resolveService,
            UnaryOperator<AuthoredManifest> mutation,
            Runnable beforeLockfileCompareAndSet) {
        ZoltManifestDocument original = manifests.document(scope.manifestPath());
        AuthoredManifest requested = mutation.apply(original.authored());
        ZoltManifestDocument edited = manifests.edit(original, requested);
        ProjectConfig standalone = scope.workspace() == null
                ? manifests.standaloneConfig(edited.source())
                : null;
        ManifestCommitResult committed = ManifestEditCommitter.commit(
                scope,
                cacheRoot,
                noResolve,
                resolveService,
                standalone,
                original.source(),
                edited.source(),
                manifests::writePrepared,
                beforeLockfileCompareAndSet);
        return new ManifestEditResult(
                original.authored(),
                edited.authored(),
                committed.resolveResult(),
                committed.manifestPath(),
                committed.lockfilePath(),
                committed.manifestChanged(),
                committed.lockfileChanged());
    }

    static void recover(Path transaction, Path projectRoot) {
        ManifestEditRecovery.recover(transaction, projectRoot);
    }

    static void requireExpectedScope(ManifestMutationScope actual, ScopeExpectation expected) {
        if (expected == null) {
            return;
        }
        if (!actual.manifestPath().equals(expected.manifestPath())
                || !actual.lockfilePath().equals(expected.lockfilePath())) {
            throw new ZoltConfigException(ActionableError.of(
                    "Dependency update scope changed before execution. No changes were written.",
                    "Run `zolt outdated --format json --schema-version 2` again and retry with a current targetId."));
        }
        requireExpectedDiscoveryConfig(actual, expected);
    }

    /**
     * A member's effective view folds in workspace-root shared configuration, so a root edit that
     * lands between planning and execution changes what the member resolves even when the member
     * manifest is untouched (design §4.5).
     */
    private static void requireExpectedDiscoveryConfig(
            ManifestMutationScope actual,
            ScopeExpectation expected) {
        if (expected.discoveryConfig().isEmpty() || actual.workspace() == null) {
            return;
        }
        ProjectConfig current = actual.workspace().members().stream()
                .filter(candidate -> candidate.directory().toAbsolutePath().normalize()
                        .equals(actual.manifestRoot()))
                .findFirst()
                .orElseThrow(() -> new ZoltConfigException(
                        "Workspace dependency update scope changed before execution. No changes were written."))
                .config();
        if (!current.equals(expected.discoveryConfig().orElseThrow())) {
            throw new ZoltConfigException(
                    "Workspace policy changed while dependency updates were being planned. No changes were written; retry against the current workspace.");
        }
    }
}
