package sh.zolt.update;

import sh.zolt.dependency.UpdateClass;
import sh.zolt.dependency.VersionClassifier;
import sh.zolt.dependency.VersionComparator;
import sh.zolt.dependency.VersionStability;
import sh.zolt.error.ActionableException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.VersionPolicy;
import sh.zolt.workspace.WorkspaceConfig;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Validates a caller-selected exact destination without consulting repository metadata. */
public final class ExactUpdatePlanner {
    private final UpdateTargetCatalog catalog = new UpdateTargetCatalog();
    private final VersionComparator comparator = new VersionComparator();
    private final VersionClassifier classifier = new VersionClassifier();

    public ExactUpdatePlan plan(
            ProjectConfig config,
            String manifestPath,
            String lockfilePath,
            UpdateTargetId targetId,
            ExactUpdateOptions options) {
        UpdateTarget target;
        try {
            target = catalog.require(config, manifestPath, lockfilePath, targetId);
        } catch (IllegalArgumentException exception) {
            throw unknownTarget(targetId);
        }
        return plan(target, options);
    }

    public ExactUpdatePlan plan(
            WorkspaceConfig config,
            String manifestPath,
            String lockfilePath,
            UpdateTargetId targetId,
            ExactUpdateOptions options) {
        UpdateTarget target;
        try {
            target = catalog.require(config, manifestPath, lockfilePath, targetId);
        } catch (IllegalArgumentException exception) {
            throw unknownTarget(targetId);
        }
        return plan(target, options);
    }

    public ExactUpdatePlan plan(UpdateTarget target, ExactUpdateOptions options) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        requireUpdateable(target);
        String destination = options.toVersion();
        Optional<VersionPolicy.Violation> violation = VersionPolicy.violation(contextOf(target.surface()), destination);
        if (violation.isPresent()) {
            VersionPolicy.Violation problem = violation.orElseThrow();
            throw new ActionableException(
                    "Version `" + String.valueOf(destination) + "` is invalid for target `" + target.targetId()
                            + "` (" + problem.rule() + ").",
                    problem.guidance());
        }
        if (VersionStability.of(destination) == VersionStability.PRERELEASE && !options.includePrereleases()) {
            throw new ActionableException(
                    "Version `" + destination + "` is a prerelease.",
                    "Pass `--include-prereleases` to select this exact prerelease destination.");
        }

        String current = target.currentVersion();
        if (destination.equals(current)) {
            return new ExactUpdatePlan(target, current, destination, Optional.empty(), false, List.of());
        }
        int comparison = comparator.compare(destination, current);
        if (comparison < 0) {
            throw new ActionableException(
                    "Exact update would downgrade target `" + target.targetId() + "` from `" + current + "` to `"
                            + destination + "`.",
                    "Choose a version strictly newer than `" + current + "`; exact downgrades are not supported.");
        }
        if (comparison == 0) {
            throw new ActionableException(
                    "Version `" + destination + "` does not advance target `" + target.targetId() + "` from `"
                            + current + "`.",
                    "Use the exact current spelling for a no-op, or choose a version that compares strictly newer.");
        }

        UpdateClass changeClass = classifier.classify(current, destination);
        return new ExactUpdatePlan(
                target,
                current,
                destination,
                Optional.of(changeClass),
                true,
                aliasWarning(target, destination));
    }

    private static void requireUpdateable(UpdateTarget target) {
        if (!target.updateable()) {
            throw new ActionableException(
                    "Zolt update target `" + target.targetId() + "` is not updateable.",
                    target.updateBlocker().orElse("Select an updateable target from the current schema-v2 report."));
        }
    }

    private static ActionableException unknownTarget(UpdateTargetId targetId) {
        return new ActionableException(
                "Unknown Zolt update target `" + targetId + "`.",
                "Run `zolt outdated --format json --schema-version 2` again and use a targetId from the current report.");
    }

    private static VersionPolicy.Context contextOf(OutdatedSurface surface) {
        return switch (surface) {
            case VERSION_ALIAS -> VersionPolicy.Context.VERSION_ALIAS;
            case DEPENDENCY, ANNOTATION_PROCESSOR -> VersionPolicy.Context.EXTERNAL_DEPENDENCY;
            case PLATFORM -> VersionPolicy.Context.PLATFORM;
            case DEPENDENCY_CONSTRAINT -> VersionPolicy.Context.CONSTRAINT;
            case EXEC_TOOL_COORDINATE, PROTOBUF_TOOL, OPENAPI_TOOL -> VersionPolicy.Context.TOOL_DEPENDENCY;
        };
    }

    private static List<String> aliasWarning(UpdateTarget target, String destination) {
        if (target.surface() != OutdatedSurface.VERSION_ALIAS) {
            return List.of();
        }
        return List.of("Alias `" + target.identifier() + "` " + target.currentVersion() + " -> " + destination
                + " updates " + target.governs().size() + " referencing coordinate(s): "
                + String.join(", ", target.governs()) + ".");
    }
}
