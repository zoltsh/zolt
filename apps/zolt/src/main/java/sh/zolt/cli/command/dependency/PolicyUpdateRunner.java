package sh.zolt.cli.command.dependency;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.cli.command.CommandResolveOutput;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.update.UpdateEngine;
import sh.zolt.update.UpdateOptions;
import sh.zolt.update.UpdatePlan;
import sh.zolt.update.UpdatePlanJsonRenderer;
import sh.zolt.update.UpdatePlanTextRenderer;
import sh.zolt.update.UpdatePlanningScope;
import java.nio.file.Path;
import picocli.CommandLine.Model.CommandSpec;

/** Preserves the existing metadata-driven update behavior and schema-v1 output. */
final class PolicyUpdateRunner {
    private final ManifestMutationServices manifests;
    private final ResolveService resolveService;
    private final UpdateEngine engine;
    private final DependencyUpdateScopeResolver scopeResolver;
    private final Runnable beforeExecution;

    PolicyUpdateRunner(
            ManifestMutationServices manifests,
            ResolveService resolveService,
            UpdateEngine engine,
            DependencyUpdateScopeResolver scopeResolver,
            Runnable beforeExecution) {
        this.manifests = manifests;
        this.resolveService = resolveService;
        this.engine = engine;
        this.scopeResolver = scopeResolver;
        this.beforeExecution = beforeExecution;
    }

    void run(
            CommandSpec spec,
            Path projectRoot,
            Path cacheRoot,
            UpdateOptions options,
            boolean dryRun,
            boolean noResolve,
            boolean json) {
        PlannedUpdate planned = ManifestEditTransaction.inspectLocked(projectRoot, lockRoot -> {
            ResolvedUpdateScope scope = scopeResolver.policyScope(projectRoot, lockRoot);
            return new PlannedUpdate(scope, engine.plan(
                    new UpdatePlanningScope(
                            scope.manifest(), scope.discovery(), scope.manifestPath(), scope.lockfilePath()),
                    options));
        });
        if (json) {
            runJson(spec, projectRoot, cacheRoot, planned, dryRun, noResolve);
        } else {
            runText(spec, projectRoot, cacheRoot, planned, dryRun, noResolve);
        }
    }

    private void runText(
            CommandSpec spec,
            Path projectRoot,
            Path cacheRoot,
            PlannedUpdate planned,
            boolean dryRun,
            boolean noResolve) {
        UpdatePlanTextRenderer renderer = new UpdatePlanTextRenderer();
        if (dryRun || !planned.plan().hasEdits()) {
            CommandOutput.printAndFlush(spec, renderer.render(planned.plan(), dryRun));
            return;
        }
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        ManifestEditResult edit = execute(projectRoot, cacheRoot, planned, noResolve);
        CommandOutput.printAndFlush(spec, renderer.render(planned.plan(), false));
        if (noResolve) {
            output.detail("Skipped resolve; run zolt resolve to refresh zolt.lock.");
        } else if (edit.resolveResult() != null) {
            CommandResolveOutput.print(spec, edit.resolveResult());
        }
    }

    private void runJson(
            CommandSpec spec,
            Path projectRoot,
            Path cacheRoot,
            PlannedUpdate planned,
            boolean dryRun,
            boolean noResolve) {
        if (!dryRun && planned.plan().hasEdits()) {
            execute(projectRoot, cacheRoot, planned, noResolve);
        }
        CommandOutput.printAndFlush(spec, new UpdatePlanJsonRenderer().render(planned.plan(), dryRun));
    }

    private ManifestEditResult execute(
            Path projectRoot,
            Path cacheRoot,
            PlannedUpdate planned,
            boolean noResolve) {
        beforeExecution.run();
        return ManifestEditTransaction.execute(
                projectRoot,
                cacheRoot,
                noResolve,
                manifests,
                resolveService,
                planned.scope().expectation(),
                current -> applyCurrentPlan(current, planned));
    }

    private AuthoredManifest applyCurrentPlan(AuthoredManifest current, PlannedUpdate planned) {
        if (!current.equals(planned.scope().manifest())) {
            throw new ZoltConfigException(
                    "zolt.toml changed while dependency updates were being planned. No changes were written; retry against the current manifest.");
        }
        return engine.apply(current, planned.plan());
    }

    private record PlannedUpdate(ResolvedUpdateScope scope, UpdatePlan plan) {
    }
}
