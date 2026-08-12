package sh.zolt.cli.command.dependency;

import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.toml.WorkspaceConfigParser;
import sh.zolt.workspace.toml.WorkspaceManifestDocument;
import sh.zolt.workspace.toml.WorkspaceTomlWriter;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Failure-safe workspace-root manifest edit using the shared manifest/lock journal. */
final class WorkspaceManifestEditTransaction {
    private WorkspaceManifestEditTransaction() {
    }

    static ManifestCommitResult execute(
            Path workspaceRoot,
            Path cacheRoot,
            boolean noResolve,
            WorkspaceConfigParser parser,
            WorkspaceTomlWriter writer,
            ResolveService resolveService,
            ScopeExpectation expectation,
            UnaryOperator<WorkspaceConfig> mutation) {
        return execute(
                workspaceRoot,
                cacheRoot,
                noResolve,
                parser,
                writer,
                resolveService,
                expectation,
                mutation,
                () -> {});
    }

    static ManifestCommitResult execute(
            Path workspaceRoot,
            Path cacheRoot,
            boolean noResolve,
            WorkspaceConfigParser parser,
            WorkspaceTomlWriter writer,
            ResolveService resolveService,
            ScopeExpectation expectation,
            UnaryOperator<WorkspaceConfig> mutation,
            Runnable beforeLockfileCompareAndSet) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(expectation, "expectation");
        Objects.requireNonNull(mutation, "mutation");
        Objects.requireNonNull(beforeLockfileCompareAndSet, "beforeLockfileCompareAndSet");
        return ManifestMutationLockGuard.withLock(workspaceRoot, lockRoot -> {
            ManifestEditRecovery.recoverAll(workspaceRoot, lockRoot);
            ManifestMutationScope scope = ManifestMutationScope.discoverWorkspaceRoot(
                    workspaceRoot, lockRoot, expectation.manifestPath());
            ManifestEditTransaction.requireExpectedScope(scope, expectation);
            WorkspaceManifestDocument original = parser.parseWorkspaceDocument(scope.manifestPath());
            WorkspaceConfig requested = mutation.apply(original.config());
            WorkspaceManifestDocument edited = writer.patchDocument(original, requested);
            return ManifestEditCommitter.commit(
                    scope,
                    cacheRoot,
                    noResolve,
                    resolveService,
                    null,
                    original.source(),
                    edited.source(),
                    (path, ignoredOriginal, ignoredEdited) -> writer.writePrepared(path, original, edited),
                    beforeLockfileCompareAndSet);
        });
    }
}
