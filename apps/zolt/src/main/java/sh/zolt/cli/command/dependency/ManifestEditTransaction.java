package sh.zolt.cli.command.dependency;

import sh.zolt.error.ActionableError;
import sh.zolt.error.ActionableException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltManifestDocument;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.toml.ZoltTomlWriter;
import sh.zolt.workspace.service.WorkspaceMember;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** Failure-safe manifest and lockfile edit transaction shared by every public mutation command. */
final class ManifestEditTransaction {
    private ManifestEditTransaction() {
    }

    static ManifestEditResult execute(
            Path projectRoot,
            Path cacheRoot,
            boolean noResolve,
            ZoltTomlParser parser,
            ZoltTomlWriter writer,
            ResolveService resolveService,
            UnaryOperator<ProjectConfig> mutation) {
        return execute(
                projectRoot,
                cacheRoot,
                noResolve,
                parser,
                writer,
                resolveService,
                null,
                mutation,
                () -> {});
    }

    static ManifestEditResult execute(
            Path projectRoot,
            Path cacheRoot,
            boolean noResolve,
            ZoltTomlParser parser,
            ZoltTomlWriter writer,
            ResolveService resolveService,
            ScopeExpectation expectation,
            UnaryOperator<ProjectConfig> mutation) {
        return execute(
                projectRoot,
                cacheRoot,
                noResolve,
                parser,
                writer,
                resolveService,
                expectation,
                mutation,
                () -> {});
    }

    /** Test seam for a deterministic writer arriving after staging but before the lockfile CAS. */
    static ManifestEditResult execute(
            Path projectRoot,
            Path cacheRoot,
            boolean noResolve,
            ZoltTomlParser parser,
            ZoltTomlWriter writer,
            ResolveService resolveService,
            UnaryOperator<ProjectConfig> mutation,
            Runnable beforeLockfileCompareAndSet) {
        return execute(
                projectRoot,
                cacheRoot,
                noResolve,
                parser,
                writer,
                resolveService,
                null,
                mutation,
                beforeLockfileCompareAndSet);
    }

    private static ManifestEditResult execute(
            Path projectRoot,
            Path cacheRoot,
            boolean noResolve,
            ZoltTomlParser parser,
            ZoltTomlWriter writer,
            ResolveService resolveService,
            ScopeExpectation expectation,
            UnaryOperator<ProjectConfig> mutation,
            Runnable beforeLockfileCompareAndSet) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(mutation, "mutation");
        Objects.requireNonNull(beforeLockfileCompareAndSet, "beforeLockfileCompareAndSet");
        return ManifestMutationLockGuard.withLock(projectRoot, lockRoot -> executeLocked(
                projectRoot,
                lockRoot,
                cacheRoot,
                noResolve,
                parser,
                writer,
                resolveService,
                expectation,
                mutation,
                beforeLockfileCompareAndSet));
    }

    /** Recovers pending edits and performs a read-only decision under the mutation lock. */
    static <T> T inspect(
            Path projectRoot,
            ZoltTomlParser parser,
            Function<ProjectConfig, T> inspection) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(inspection, "inspection");
        return inspectLocked(projectRoot, lockRoot -> {
            ManifestMutationScope scope = ManifestMutationScope.discover(projectRoot, lockRoot);
            return inspection.apply(parser.parse(scope.manifestPath()));
        });
    }

    /** Recovers pending edits and performs a workspace-wide inspection under the mutation lock. */
    static <T> T inspectLocked(Path projectRoot, Function<Path, T> inspection) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(inspection, "inspection");
        return ManifestMutationLockGuard.withLock(projectRoot, lockRoot -> {
            ManifestEditRecovery.recoverAll(projectRoot, lockRoot);
            return inspection.apply(lockRoot);
        });
    }

    private static ManifestEditResult executeLocked(
            Path projectRoot,
            Path lockRoot,
            Path cacheRoot,
            boolean noResolve,
            ZoltTomlParser parser,
            ZoltTomlWriter writer,
            ResolveService resolveService,
            ScopeExpectation expectation,
            UnaryOperator<ProjectConfig> mutation,
            Runnable beforeLockfileCompareAndSet) {
        ManifestEditRecovery.recoverAll(projectRoot, lockRoot);
        ManifestMutationScope scope = ManifestMutationScope.discover(projectRoot, lockRoot);
        requireExpectedScope(scope, expectation);
        ZoltManifestDocument original = parser.parseDocument(scope.manifestPath());
        ProjectConfig requested = mutation.apply(original.config());
        ZoltManifestDocument edited = writer.patchDocument(original, requested);
        ManifestCommitResult committed = ManifestEditCommitter.commit(
                scope,
                cacheRoot,
                noResolve,
                resolveService,
                edited.config(),
                original.source(),
                edited.source(),
                (path, ignoredOriginal, ignoredEdited) -> writer.writePrepared(path, original, edited),
                beforeLockfileCompareAndSet);
        return new ManifestEditResult(
                original.config(),
                edited.config(),
                committed.resolveResult(),
                committed.manifestPath(),
                committed.lockfilePath(),
                committed.manifestChanged(),
                committed.lockfileChanged());
    }

    static void recover(Path transaction, Path projectRoot) {
        ManifestEditRecovery.recover(transaction, projectRoot);
    }

    static void requireExpectedScope(
            ManifestMutationScope actual,
            ScopeExpectation expected) {
        if (expected == null) {
            return;
        }
        if (!actual.manifestPath().equals(expected.manifestPath())
                || !actual.lockfilePath().equals(expected.lockfilePath())) {
            throw new ZoltConfigException(ActionableError.of(
                    "Dependency update scope changed before execution. No changes were written.",
                    "Run `zolt outdated --format json --schema-version 2` again and retry with a current targetId."));
        }
        if (actual.workspace() == null) {
            return;
        }
        WorkspaceUpdateContext context = WorkspaceUpdateContext.from(actual.workspace());
        requireExpectedDiscoveryConfig(actual, expected, context);
        var blockers = context.targetBlockers();
        for (var targetKey : expected.targetKeys()) {
            String blocker = blockers.get(targetKey);
            if (blocker != null) {
                throw new ActionableException(
                        "Zolt update target `" + targetKey.identifier() + "` is not updateable.",
                        blocker);
            }
        }
    }

    private static void requireExpectedDiscoveryConfig(
            ManifestMutationScope actual,
            ScopeExpectation expected,
            WorkspaceUpdateContext context) {
        if (expected.discoveryConfig().isEmpty()) {
            return;
        }
        WorkspaceMember member = actual.workspace().members().stream()
                .filter(candidate -> candidate.directory().toAbsolutePath().normalize().equals(actual.manifestRoot()))
                .findFirst()
                .orElseThrow(() -> new ZoltConfigException(
                        "Workspace dependency update scope changed before execution. No changes were written."));
        if (!context.effectiveConfig(member).equals(expected.discoveryConfig().orElseThrow())) {
            throw new ZoltConfigException(
                    "Workspace policy changed while dependency updates were being planned. No changes were written; retry against the current workspace.");
        }
    }

}
