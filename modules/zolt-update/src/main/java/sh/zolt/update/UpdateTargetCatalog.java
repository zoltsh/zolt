package sh.zolt.update;

import sh.zolt.project.ProjectConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Builds deterministic automation targets from the same surfaces used by outdated and update. */
public final class UpdateTargetCatalog {
    private final SurfaceCollector collector;

    public UpdateTargetCatalog() {
        this.collector = new SurfaceCollector();
    }

    public List<UpdateTarget> collect(
            ProjectConfig config,
            String manifestPath,
            String lockfilePath) {
        return entries(config, manifestPath, lockfilePath).stream()
                .map(Entry::target)
                .toList();
    }

    public UpdateTarget require(
            ProjectConfig config,
            String manifestPath,
            String lockfilePath,
            UpdateTargetId targetId) {
        return requireEntry(config, manifestPath, lockfilePath, targetId).target();
    }

    List<Entry> entries(
            ProjectConfig config,
            String manifestPath,
            String lockfilePath) {
        Objects.requireNonNull(config, "config");
        String canonicalManifest = UpdateTargetId.requireCanonicalPath(manifestPath, "manifest path");
        String canonicalLockfile = UpdateTargetId.requireCanonicalPath(lockfilePath, "lockfile path");
        Map<UpdateTargetId, Entry> entries = new LinkedHashMap<>();
        for (SurfaceRequest request : collector.collect(config)) {
            UpdateTarget target = target(request, canonicalManifest, canonicalLockfile);
            addUnique(entries, new Entry(target, request));
        }
        return List.copyOf(entries.values());
    }

    static void addUnique(Map<UpdateTargetId, Entry> entries, Entry entry) {
        Entry previous = entries.putIfAbsent(entry.target().targetId(), entry);
        if (previous != null) {
            throw new IllegalStateException("Duplicate Zolt update target ID " + entry.target().targetId() + ".");
        }
    }

    Entry requireEntry(
            ProjectConfig config,
            String manifestPath,
            String lockfilePath,
            UpdateTargetId targetId) {
        Objects.requireNonNull(targetId, "targetId");
        return entries(config, manifestPath, lockfilePath).stream()
                .filter(entry -> entry.target().targetId().equals(targetId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Zolt update target `" + targetId + "`."));
    }

    private UpdateTarget target(
            SurfaceRequest request,
            String manifestPath,
            String lockfilePath) {
        boolean updateable = UpdateApplicability.isApplicable(request.surface());
        Optional<String> blocker = updateable
                ? Optional.empty()
                : Optional.of(UpdateApplicability.reason(request.surface()));
        return new UpdateTarget(
                UpdateTargetId.create(manifestPath, request.surface(), request.section(), request.identifier()),
                manifestPath,
                lockfilePath,
                request.surface(),
                request.identifier(),
                request.section(),
                request.currentVersion(),
                updateable,
                blocker,
                request.governs());
    }

    record Entry(UpdateTarget target, SurfaceRequest request) {
    }
}
