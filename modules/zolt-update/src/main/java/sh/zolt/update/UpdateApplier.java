package sh.zolt.update;

import sh.zolt.dependency.UpdateClass;
import sh.zolt.project.DependencyConstraint;
import sh.zolt.project.DependencyPolicySettings;
import sh.zolt.project.DependencySection;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.dependency.ProjectConfigDependencyMutator;
import sh.zolt.workspace.WorkspaceConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Applies policy and exact update plans through one metadata-preserving mutation path. */
public final class UpdateApplier {

    public ProjectConfig apply(ProjectConfig config, UpdatePlan plan) {
        Objects.requireNonNull(plan, "plan");
        return apply(config, plan.edits());
    }

    public ProjectConfig apply(ProjectConfig config, ExactUpdatePlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!plan.changed()) {
            return Objects.requireNonNull(config, "config");
        }
        UpdateTarget target = plan.target();
        UpdateEdit edit = new UpdateEdit(
                target.surface(),
                target.identifier(),
                target.section(),
                plan.fromVersion(),
                plan.toVersion(),
                plan.changeClass().orElseThrow(),
                target.governs());
        return apply(config, List.of(edit));
    }

    public WorkspaceConfig apply(WorkspaceConfig config, ExactUpdatePlan plan) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(plan, "plan");
        if (!plan.changed()) {
            return config;
        }
        UpdateTarget target = plan.target();
        if (target.surface() != OutdatedSurface.PLATFORM || !"[platforms]".equals(target.section())) {
            throw new IllegalArgumentException(
                    "Workspace-root update surface `" + target.surface().jsonName() + "` is not mutable.");
        }
        Map<String, String> platforms = new LinkedHashMap<>(config.platforms());
        String current = platforms.get(target.identifier());
        if (!plan.fromVersion().equals(current)) {
            throw new IllegalArgumentException(
                    "Workspace-root platform `" + target.identifier() + "` no longer matches the update plan.");
        }
        platforms.put(target.identifier(), plan.toVersion());
        return config.withPlatforms(platforms);
    }

    private ProjectConfig apply(ProjectConfig config, List<UpdateEdit> edits) {
        ProjectConfig updated = Objects.requireNonNull(config, "config");
        Map<String, String> aliases = new LinkedHashMap<>(config.versionAliases());
        boolean aliasChanged = false;
        for (UpdateEdit edit : edits) {
            switch (edit.surface()) {
                case VERSION_ALIAS -> {
                    aliases.put(edit.identifier(), edit.toVersion());
                    aliasChanged = true;
                }
                case DEPENDENCY, ANNOTATION_PROCESSOR -> updated = ProjectConfigDependencyMutator.addDependency(
                        updated, sectionOf(edit.section()), edit.identifier(), edit.toVersion());
                case PLATFORM ->
                    updated = ProjectConfigDependencyMutator.addPlatform(updated, edit.identifier(), edit.toVersion());
                case DEPENDENCY_CONSTRAINT -> updated = applyConstraint(updated, edit.identifier(), edit.toVersion());
                default -> throw new IllegalArgumentException(
                        "Update surface `" + edit.surface().jsonName() + "` is not mutable.");
            }
        }
        return aliasChanged ? updated.withVersionAliases(aliases) : updated;
    }

    private static ProjectConfig applyConstraint(ProjectConfig config, String coordinate, String version) {
        Map<String, DependencyConstraint> constraints = new LinkedHashMap<>(config.dependencyPolicy().constraints());
        DependencyConstraint existing = constraints.get(coordinate);
        if (existing == null) {
            return config;
        }
        constraints.put(
                coordinate,
                new DependencyConstraint(coordinate, version, existing.versionRef(), existing.kind(), existing.reason()));
        DependencyPolicySettings policy = new DependencyPolicySettings(
                config.dependencyPolicy().exclusions(), constraints, config.dependencyPolicy().failOnVersionConflict());
        return config.withDependencyPolicy(policy);
    }

    private static DependencySection sectionOf(String section) {
        return switch (section) {
            case "[dependencies]" -> DependencySection.MAIN;
            case "[api.dependencies]" -> DependencySection.API;
            case "[runtime.dependencies]" -> DependencySection.RUNTIME;
            case "[provided.dependencies]" -> DependencySection.PROVIDED;
            case "[dev.dependencies]" -> DependencySection.DEV;
            case "[test.dependencies]" -> DependencySection.TEST;
            case "[annotationProcessors]" -> DependencySection.PROCESSOR;
            case "[test.annotationProcessors]" -> DependencySection.TEST_PROCESSOR;
            default -> throw new IllegalStateException("Unmapped dependency section: " + section);
        };
    }
}
