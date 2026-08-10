package sh.zolt.update;

import sh.zolt.dependency.UpdateClass;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One validated exact-target change, or a successful same-version no-op. */
public record ExactUpdatePlan(
        UpdateTarget target,
        String fromVersion,
        String toVersion,
        Optional<UpdateClass> changeClass,
        boolean changed,
        List<String> warnings) {
    public ExactUpdatePlan {
        target = Objects.requireNonNull(target, "target");
        fromVersion = Objects.requireNonNull(fromVersion, "fromVersion");
        toVersion = Objects.requireNonNull(toVersion, "toVersion");
        changeClass = changeClass == null ? Optional.empty() : changeClass;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        if (!fromVersion.equals(target.currentVersion())) {
            throw new IllegalArgumentException("Exact update plan must start at the target's current version.");
        }
        if (changed != changeClass.isPresent()) {
            throw new IllegalArgumentException("A changed exact update requires a change class, and a no-op cannot have one.");
        }
        if (!changed && !fromVersion.equals(toVersion)) {
            throw new IllegalArgumentException("An exact update no-op must preserve the exact version string.");
        }
    }
}
