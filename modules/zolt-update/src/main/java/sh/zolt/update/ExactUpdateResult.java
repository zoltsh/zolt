package sh.zolt.update;

import java.util.List;
import java.util.Objects;

/** Exact plan plus the actual filesystem and resolve effects of this invocation. */
public record ExactUpdateResult(
        ExactUpdatePlan plan,
        boolean dryRun,
        boolean applied,
        boolean resolved,
        List<String> changedFiles) {
    public ExactUpdateResult {
        plan = Objects.requireNonNull(plan, "plan");
        changedFiles = changedFiles == null ? List.of() : changedFiles.stream()
                .map(path -> UpdateTargetId.requireCanonicalPath(path, "changed file"))
                .toList();
        if (dryRun && (applied || resolved || !changedFiles.isEmpty())) {
            throw new IllegalArgumentException("A dry run cannot apply, resolve, or change files.");
        }
        if (applied && !plan.changed()) {
            throw new IllegalArgumentException("An exact no-op cannot be applied.");
        }
        if (resolved && !applied) {
            throw new IllegalArgumentException("An exact update cannot resolve without applying its manifest edit.");
        }
        if (!applied && !changedFiles.isEmpty()) {
            throw new IllegalArgumentException("An unapplied exact update cannot report changed files.");
        }
    }

    public boolean changed() {
        return plan.changed();
    }
}
