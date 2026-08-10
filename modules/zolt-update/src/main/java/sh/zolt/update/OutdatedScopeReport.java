package sh.zolt.update;

import java.util.List;
import java.util.Objects;

/** The reportable entries for one scope (a single project, or one workspace member or root). */
public record OutdatedScopeReport(
        String label,
        String manifestPath,
        String lockfilePath,
        List<OutdatedEntry> entries) {
    public OutdatedScopeReport {
        label = Objects.requireNonNull(label, "label");
        manifestPath = UpdateTargetId.requireCanonicalPath(manifestPath, "manifest path");
        lockfilePath = UpdateTargetId.requireCanonicalPath(lockfilePath, "lockfile path");
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public OutdatedScopeReport(String label, List<OutdatedEntry> entries) {
        this(label, "zolt.toml", "zolt.lock", entries);
    }
}
