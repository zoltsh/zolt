package sh.zolt.cli.command.dependency;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.cli.command.CommandResolveOutput;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.toml.ZoltTomlWriter;
import sh.zolt.update.UpdateEngine;
import sh.zolt.update.UpdateOptions;
import sh.zolt.update.UpdatePlan;
import sh.zolt.update.UpdatePlanJsonRenderer;
import sh.zolt.update.UpdatePlanningScope;
import sh.zolt.update.UpdatePlanTextRenderer;
import sh.zolt.update.UpdateTargetId;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import picocli.CommandLine.Model.CommandSpec;

/** Preserves the existing metadata-driven update behavior and schema-v1 output. */
final class PolicyUpdateRunner {
    private final ZoltTomlParser tomlParser;
    private final ZoltTomlWriter tomlWriter;
    private final ResolveService resolveService;
    private final UpdateEngine engine;
    private final DependencyUpdateScopeResolver scopeResolver;
    private final Runnable beforeExecution;

    PolicyUpdateRunner(
            ZoltTomlParser tomlParser,
            ZoltTomlWriter tomlWriter,
            ResolveService resolveService,
            UpdateEngine engine,
            DependencyUpdateScopeResolver scopeResolver,
            Runnable beforeExecution) {
        this.tomlParser = tomlParser;
        this.tomlWriter = tomlWriter;
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
            UpdatePlanningScope planningScope = new UpdatePlanningScope(
                    scope.config(),
                    scope.discoveryConfig(),
                    scope.manifestPath(),
                    scope.lockfilePath(),
                    scope.targetBlockers());
            return new PlannedUpdate(scope, engine.plan(planningScope, options));
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
        ResolvedUpdateScope scope = planned.scope();
        ScopeExpectation expectation = new ScopeExpectation(
                scope.absoluteManifestPath(),
                scope.absoluteLockfilePath(),
                targetIds(scope, planned.plan()),
                Optional.of(scope.discoveryConfig()));
        return ManifestEditTransaction.execute(
                projectRoot,
                cacheRoot,
                noResolve,
                tomlParser,
                tomlWriter,
                resolveService,
                expectation,
                current -> applyCurrentPlan(current, planned));
    }

    private ProjectConfig applyCurrentPlan(ProjectConfig current, PlannedUpdate planned) {
        if (!current.equals(planned.scope().config())) {
            throw new ZoltConfigException(
                    "zolt.toml changed while dependency updates were being planned. No changes were written; retry against the current manifest.");
        }
        return engine.apply(current, planned.plan());
    }

    private static List<UpdateTargetId> targetIds(ResolvedUpdateScope scope, UpdatePlan plan) {
        return plan.edits().stream()
                .map(edit -> UpdateTargetId.create(
                        scope.manifestPath(), edit.surface(), edit.section(), edit.identifier()))
                .toList();
    }

    private record PlannedUpdate(ResolvedUpdateScope scope, UpdatePlan plan) {
    }
}
