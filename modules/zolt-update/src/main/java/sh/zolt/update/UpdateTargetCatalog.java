package sh.zolt.update;

import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.WorkspaceConfig;
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
        return collect(config, manifestPath, lockfilePath, Map.of());
    }

    public List<UpdateTarget> collect(
            ProjectConfig config,
            String manifestPath,
            String lockfilePath,
            Map<UpdateTargetId, String> blockers) {
        return entries(config, manifestPath, lockfilePath, blockers).stream()
                .map(Entry::target)
                .toList();
    }

    public List<UpdateTarget> collect(
            WorkspaceConfig config,
            String manifestPath,
            String lockfilePath) {
        return collect(config, manifestPath, lockfilePath, Map.of());
    }

    public List<UpdateTarget> collect(
            WorkspaceConfig config,
            String manifestPath,
            String lockfilePath,
            Map<UpdateTargetId, String> blockers) {
        return entries(config, manifestPath, lockfilePath, blockers).stream()
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

    public UpdateTarget require(
            WorkspaceConfig config,
            String manifestPath,
            String lockfilePath,
            UpdateTargetId targetId) {
        return requireEntry(config, manifestPath, lockfilePath, targetId).target();
    }

    List<Entry> entries(
            ProjectConfig config,
            String manifestPath,
            String lockfilePath) {
        return entries(config, manifestPath, lockfilePath, Map.of());
    }

    List<Entry> entries(
            ProjectConfig config,
            String manifestPath,
            String lockfilePath,
            Map<UpdateTargetId, String> blockers) {
        Objects.requireNonNull(config, "config");
        String canonicalManifest = UpdateTargetId.requireCanonicalPath(manifestPath, "manifest path");
        String canonicalLockfile = UpdateTargetId.requireCanonicalPath(lockfilePath, "lockfile path");
        Map<UpdateTargetId, Entry> entries = new LinkedHashMap<>();
        for (SurfaceRequest request : collector.collect(config)) {
            UpdateTarget target = contextualTarget(request, canonicalManifest, canonicalLockfile, blockers);
            addUnique(entries, new Entry(target, request));
        }
        return List.copyOf(entries.values());
    }

    List<Entry> entries(
            WorkspaceConfig config,
            String manifestPath,
            String lockfilePath) {
        return entries(config, manifestPath, lockfilePath, Map.of());
    }

    List<Entry> entries(
            WorkspaceConfig config,
            String manifestPath,
            String lockfilePath,
            Map<UpdateTargetId, String> blockers) {
        Objects.requireNonNull(config, "config");
        String canonicalManifest = UpdateTargetId.requireCanonicalPath(manifestPath, "manifest path");
        String canonicalLockfile = UpdateTargetId.requireCanonicalPath(lockfilePath, "lockfile path");
        Map<UpdateTargetId, Entry> entries = new LinkedHashMap<>();
        for (SurfaceRequest request : collector.collect(config)) {
            UpdateTarget target = contextualTarget(request, canonicalManifest, canonicalLockfile, blockers);
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

    Entry requireEntry(
            WorkspaceConfig config,
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

    private UpdateTarget contextualTarget(
            SurfaceRequest request,
            String manifestPath,
            String lockfilePath,
            Map<UpdateTargetId, String> blockers) {
        UpdateTarget target = target(request, manifestPath, lockfilePath);
        Map<UpdateTargetId, String> contextualBlockers = blockers == null ? Map.of() : blockers;
        String blocker = contextualBlockers.get(target.targetId());
        return blocker == null ? target : target.blocked(blocker);
    }

    record Entry(UpdateTarget target, SurfaceRequest request) {
    }
}
