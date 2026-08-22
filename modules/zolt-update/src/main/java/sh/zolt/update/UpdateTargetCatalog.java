package sh.zolt.update;

import sh.zolt.manifest.authored.AuthoredManifest;
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
            AuthoredManifest manifest,
            String manifestPath,
            String lockfilePath) {
        return entries(manifest, manifestPath, lockfilePath).stream()
                .map(Entry::target)
                .toList();
    }

    public UpdateTarget require(
            AuthoredManifest manifest,
            String manifestPath,
            String lockfilePath,
            UpdateTargetId targetId) {
        return requireEntry(manifest, manifestPath, lockfilePath, targetId).target();
    }

    public List<UpdateTargetReference> references(AuthoredManifest manifest, String manifestPath) {
        return entries(manifest, manifestPath, "zolt.lock").stream()
                .map(Entry::reference)
                .toList();
    }

    List<Entry> entries(
            AuthoredManifest manifest,
            String manifestPath,
            String lockfilePath) {
        Objects.requireNonNull(manifest, "manifest");
        String rawManifest = UpdateTargetKey.requirePath(manifestPath, "manifest path");
        String rawLockfile = UpdateTargetKey.requirePath(lockfilePath, "lockfile path");
        Map<UpdateTargetKey, Entry> entries = new LinkedHashMap<>();
        for (SurfaceRequest request : collector.collect(manifest)) {
            addUnique(entries, entry(request, rawManifest, rawLockfile));
        }
        return List.copyOf(entries.values());
    }

    Entry requireEntry(
            AuthoredManifest manifest,
            String manifestPath,
            String lockfilePath,
            UpdateTargetId targetId) {
        Objects.requireNonNull(targetId, "targetId");
        return entries(manifest, manifestPath, lockfilePath).stream()
                .filter(entry -> entry.target().targetId().equals(targetId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Zolt update target `" + targetId + "`."));
    }

    static void addUnique(Map<UpdateTargetKey, Entry> entries, Entry entry) {
        Entry previous = entries.putIfAbsent(entry.key(), entry);
        if (previous != null) {
            throw new IllegalStateException("Duplicate Zolt update target identity " + entry.key() + ".");
        }
    }

    private Entry entry(
            SurfaceRequest request,
            String manifestPath,
            String lockfilePath) {
        UpdateTargetKey key = new UpdateTargetKey(
                manifestPath, request.surface(), request.section(), request.identifier());
        boolean updateable = UpdateApplicability.isApplicable(request.surface());
        Optional<String> blocker = updateable
                ? Optional.empty()
                : Optional.of(UpdateApplicability.reason(request.surface()));
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
