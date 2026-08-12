package sh.zolt.cli.command.dependency;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.cli.command.CommandResolveOutput;
import sh.zolt.error.ActionableException;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.toml.ZoltTomlWriter;
import sh.zolt.update.ExactUpdateJsonRenderer;
import sh.zolt.update.ExactUpdateOptions;
import sh.zolt.update.ExactUpdatePlan;
import sh.zolt.update.ExactUpdatePlanner;
import sh.zolt.update.ExactUpdateResult;
import sh.zolt.update.ExactUpdateTextRenderer;
import sh.zolt.update.UpdateApplier;
import sh.zolt.update.UpdateTarget;
import sh.zolt.update.UpdateTargetCatalog;
import sh.zolt.update.UpdateTargetId;
import sh.zolt.workspace.toml.WorkspaceConfigParser;
import sh.zolt.workspace.toml.WorkspaceTomlWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import picocli.CommandLine.Model.CommandSpec;

/** Selects, revalidates, and applies one opaque exact target through the shared transaction. */
final class ExactUpdateRunner {
    private final ZoltTomlParser tomlParser;
    private final ZoltTomlWriter tomlWriter;
    private final ResolveService resolveService;
    private final DependencyUpdateScopeResolver scopeResolver;
    private final Runnable beforeExecution;
    private final UpdateTargetCatalog catalog = new UpdateTargetCatalog();
    private final ExactUpdatePlanner planner = new ExactUpdatePlanner();
    private final UpdateApplier applier = new UpdateApplier();
    private final WorkspaceConfigParser workspaceParser = new WorkspaceConfigParser();
    private final WorkspaceTomlWriter workspaceWriter = new WorkspaceTomlWriter();

    ExactUpdateRunner(
            ZoltTomlParser tomlParser,
            ZoltTomlWriter tomlWriter,
            ResolveService resolveService,
            DependencyUpdateScopeResolver scopeResolver) {
        this(tomlParser, tomlWriter, resolveService, scopeResolver, () -> {});
    }

    ExactUpdateRunner(
            ZoltTomlParser tomlParser,
            ZoltTomlWriter tomlWriter,
            ResolveService resolveService,
            DependencyUpdateScopeResolver scopeResolver,
            Runnable beforeExecution) {
        this.tomlParser = tomlParser;
        this.tomlWriter = tomlWriter;
        this.resolveService = resolveService;
        this.scopeResolver = scopeResolver;
        this.beforeExecution = beforeExecution;
    }

    void run(
            CommandSpec spec,
            Path start,
            Path cacheRoot,
            UpdateTargetId targetId,
            ExactUpdateOptions options,
            boolean dryRun,
            boolean noResolve,
            boolean json) {
        SelectedExactUpdate selected = ManifestEditTransaction.inspectLocked(
                start,
                lockRoot -> select(scopeResolver.catalogScopes(start, lockRoot), targetId, options));
        ExactUpdateResult result;
        ManifestCommitResult edit = null;
        if (dryRun) {
            result = new ExactUpdateResult(selected.plan(), true, false, false, List.of());
        } else {
            beforeExecution.run();
            AtomicReference<ExactUpdatePlan> executedPlan = new AtomicReference<>();
            CatalogUpdateScope scope = selected.scope();
            ScopeExpectation expectation =
                    new ScopeExpectation(
                            scope.absoluteManifestPath(),
                            scope.absoluteLockfilePath(),
                            List.of(targetId));
            if (scope instanceof ResolvedUpdateScope project) {
                ManifestEditResult projectEdit = ManifestEditTransaction.execute(
                        project.projectDirectory(),
                        cacheRoot,
                        noResolve,
                        tomlParser,
                        tomlWriter,
                        resolveService,
                        expectation,
                        current -> {
                            ExactUpdatePlan currentPlan = planner.plan(
                                    current, project.manifestPath(), project.lockfilePath(), targetId, options);
                            executedPlan.set(currentPlan);
                            return applier.apply(current, currentPlan);
                        });
                edit = ManifestCommitResult.from(projectEdit);
            } else if (scope instanceof ResolvedWorkspaceUpdateScope workspace) {
                edit = WorkspaceManifestEditTransaction.execute(
                        workspace.projectDirectory(),
                        cacheRoot,
                        noResolve,
                        workspaceParser,
                        workspaceWriter,
                        resolveService,
                        expectation,
                        current -> {
                            ExactUpdatePlan currentPlan = planner.plan(
                                    current,
                                    workspace.manifestPath(),
                                    workspace.lockfilePath(),
                                    targetId,
                                    options);
                            executedPlan.set(currentPlan);
                            return applier.apply(current, currentPlan);
                        });
            } else {
                throw new IllegalStateException("Unknown exact-update scope " + scope.getClass().getName() + ".");
            }
            result = new ExactUpdateResult(
                    executedPlan.get(),
                    false,
                    edit.manifestChanged(),
                    edit.resolveResult() != null,
                    changedFiles(scope.mutationRoot(), edit));
        }
        render(spec, result, edit, noResolve, json);
    }

    private SelectedExactUpdate select(
            List<CatalogUpdateScope> scopes,
            UpdateTargetId targetId,
            ExactUpdateOptions options) {
        List<SelectedExactUpdate> matches = new ArrayList<>();
        for (CatalogUpdateScope scope : scopes) {
            List<UpdateTarget> targets;
            if (scope instanceof ResolvedUpdateScope project) {
                targets = catalog.collect(
                        project.config(), scope.manifestPath(), scope.lockfilePath(), scope.targetBlockers());
            } else if (scope instanceof ResolvedWorkspaceUpdateScope workspace) {
                targets = catalog.collect(
                        workspace.config(), scope.manifestPath(), scope.lockfilePath(), scope.targetBlockers());
            } else {
                throw new IllegalStateException("Unknown exact-update scope " + scope.getClass().getName() + ".");
            }
            for (UpdateTarget target : targets) {
                if (target.targetId().equals(targetId)) {
                    matches.add(new SelectedExactUpdate(scope, planner.plan(target, options)));
                }
            }
        }
        if (matches.isEmpty()) {
            throw new ActionableException(
                    "Unknown Zolt update target `" + targetId + "`.",
                    "Run `zolt outdated --format json --schema-version 2` again and retry with a current targetId.");
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("Duplicate Zolt update target ID " + targetId + ".");
        }
        return matches.getFirst();
    }

    private static List<String> changedFiles(Path mutationRoot, ManifestCommitResult edit) {
        return edit.changedPaths().stream()
                .map(path -> CanonicalUpdatePath.relative(mutationRoot, path))
                .toList();
    }

    private static void render(
            CommandSpec spec,
            ExactUpdateResult result,
            ManifestCommitResult edit,
            boolean noResolve,
            boolean json) {
        if (json) {
            CommandOutput.printAndFlush(spec, new ExactUpdateJsonRenderer().render(result));
            return;
        }
        CommandOutput.printAndFlush(spec, new ExactUpdateTextRenderer().render(result));
        if (result.applied() && noResolve) {
            CommandHumanOutput.of(spec).detail("Skipped resolve; run zolt resolve to refresh zolt.lock.");
        } else if (result.resolved() && edit != null) {
            CommandResolveOutput.print(spec, edit.resolveResult());
        }
    }

    private record SelectedExactUpdate(CatalogUpdateScope scope, ExactUpdatePlan plan) {
    }
}
