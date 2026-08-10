package sh.zolt.update;

import java.util.Locale;

/** Human-readable summary of one exact update invocation. */
public final class ExactUpdateTextRenderer {
    public String render(ExactUpdateResult result) {
        ExactUpdatePlan plan = result.plan();
        if (!plan.changed()) {
            return "Target `" + plan.target().targetId() + "` is already at " + plan.toVersion()
                    + "; no changes made.\n";
        }
        StringBuilder text = new StringBuilder(result.dryRun() ? "Planned exact update (dry run):\n" : "Updated:\n");
        text.append("  ")
                .append(plan.target().section()).append('.').append(plan.target().identifier())
                .append("  ").append(plan.fromVersion()).append(" -> ").append(plan.toVersion())
                .append("  (").append(plan.changeClass().orElseThrow().name().toLowerCase(Locale.ROOT)).append(")\n");
        for (String warning : plan.warnings()) {
            text.append("warning: ").append(warning).append('\n');
        }
        return text.toString();
    }
}
