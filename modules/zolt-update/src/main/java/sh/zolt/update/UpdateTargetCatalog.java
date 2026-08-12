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
            Map<UpdateTargetKey, String> blockers) {
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
            Map<UpdateTargetKey, String> blockers) {
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
            Map<UpdateTargetKey, String> blockers) {
        Objects.requireNonNull(config, "config");
        String rawManifest = UpdateTargetKey.requirePath(manifestPath, "manifest path");
        String rawLockfile = UpdateTargetKey.requirePath(lockfilePath, "lockfile path");
        Map<UpdateTargetKey, Entry> entries = new LinkedHashMap<>();
        for (SurfaceRequest request : collector.collect(config)) {
            addUnique(entries, entry(request, rawManifest, rawLockfile, blockers));
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
            Map<UpdateTargetKey, String> blockers) {
        Objects.requireNonNull(config, "config");
        String rawManifest = UpdateTargetKey.requirePath(manifestPath, "manifest path");
        String rawLockfile = UpdateTargetKey.requirePath(lockfilePath, "lockfile path");
        Map<UpdateTargetKey, Entry> entries = new LinkedHashMap<>();
        for (SurfaceRequest request : collector.collect(config)) {
            addUnique(entries, entry(request, rawManifest, rawLockfile, blockers));
        }
        return List.copyOf(entries.values());
    }

    public List<UpdateTargetReference> references(ProjectConfig config, String manifestPath) {
        return entries(config, manifestPath, "zolt.lock").stream()
                .map(Entry::reference)
                .toList();
    }

    public List<UpdateTargetReference> references(WorkspaceConfig config, String manifestPath) {
        return entries(config, manifestPath, "zolt.lock").stream()
                .map(Entry::reference)
                .toList();
    }

    static void addUnique(Map<UpdateTargetKey, Entry> entries, Entry entry) {
        Entry previous = entries.putIfAbsent(entry.key(), entry);
        if (previous != null) {
            throw new IllegalStateException("Duplicate Zolt update target identity " + entry.key() + ".");
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

    private Entry entry(
            SurfaceRequest request,
            String manifestPath,
            String lockfilePath,
            Map<UpdateTargetKey, String> blockers) {
        UpdateTargetKey key = new UpdateTargetKey(
                manifestPath, request.surface(), request.section(), request.identifier());
        boolean updateable = UpdateApplicability.isApplicable(request.surface());
        Optional<String> blocker = updateable
                ? Optional.empty()
                : Optional.of(UpdateApplicability.reason(request.surface()));
        String contextual = (blockers == null ? Map.<UpdateTargetKey, String>of() : blockers).get(key);
        if (contextual != null) {
            updateable = false;
            blocker = Optional.of(contextual);
        }
        return new Entry(
                new UpdateTargetReference(key, request.governs()),
                lockfilePath,
                request,
                updateable,
                blocker);
    }

    record Entry(
            UpdateTargetReference reference,
            String lockfilePath,
            SurfaceRequest request,
            boolean updateable,
            Optional<String> updateBlocker) {
        UpdateTargetKey key() {
            return reference.key();
        }

        UpdateTarget target() {
            return new UpdateTarget(
                    UpdateTargetId.create(
                            key().manifestPath(), key().surface(), key().section(), key().identifier()),
                    key().manifestPath(),
                    lockfilePath,
                    key().surface(),
                    key().identifier(),
                    key().section(),
                    request.currentVersion(),
                    updateable,
                    updateBlocker,
                    reference.governs());
        }
    }
}
