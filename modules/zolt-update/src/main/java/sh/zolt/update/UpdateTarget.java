package sh.zolt.update;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Canonical automation-facing description of one dependency-version surface. */
public record UpdateTarget(
        UpdateTargetId targetId,
        String manifestPath,
        String lockfilePath,
        OutdatedSurface surface,
        String identifier,
        String section,
        String currentVersion,
        boolean updateable,
        Optional<String> updateBlocker,
        List<String> governs) {
    public UpdateTarget {
        targetId = Objects.requireNonNull(targetId, "targetId");
        manifestPath = UpdateTargetId.requireCanonicalPath(manifestPath, "manifest path");
        lockfilePath = UpdateTargetId.requireCanonicalPath(lockfilePath, "lockfile path");
        surface = Objects.requireNonNull(surface, "surface");
        identifier = UpdateTargetId.requireCanonicalText(identifier, "identifier");
        section = UpdateTargetId.requireCanonicalText(section, "section");
        currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        updateBlocker = updateBlocker == null ? Optional.empty() : updateBlocker;
        governs = governs == null ? List.of() : List.copyOf(governs);
        if (updateable == updateBlocker.isPresent()) {
            throw new IllegalArgumentException(
                    "An updateable target cannot have a blocker, and a non-updateable target must have one.");
        }
        UpdateTargetId expected = UpdateTargetId.create(manifestPath, surface, section, identifier);
        if (!targetId.equals(expected)) {
            throw new IllegalArgumentException("Update target ID does not match its canonical identity fields.");
        }
    }

    public UpdateTarget blocked(String reason) {
        return new UpdateTarget(
                targetId,
                manifestPath,
                lockfilePath,
                surface,
                identifier,
                section,
                currentVersion,
                false,
                Optional.of(Objects.requireNonNull(reason, "reason")),
                governs);
    }

    public UpdateTargetKey key() {
        return new UpdateTargetKey(manifestPath, surface, section, identifier);
    }
}
