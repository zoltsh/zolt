package sh.zolt.update;

import sh.zolt.dependency.VersionCandidates;
import sh.zolt.dependency.VersionClassifier;
import sh.zolt.maven.metadata.MetadataDiscovery;
import sh.zolt.maven.metadata.VersionDiscovery;
import sh.zolt.maven.repository.RepositoryAccess;
import sh.zolt.maven.repository.RepositoryAccessPlanner;
import sh.zolt.manifest.authored.AuthoredManifest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plans and applies dependency-version updates. Planning discovers candidates per surface, picks the
 * target at the requested ceiling, and records applicable changes as edits and unsupported ones as
 * skips. Applying goes through {@link UpdateApplier} and therefore through the authored mutation
 * model, so unrelated metadata is preserved and a literal is never written over a versionRef.
 */
public final class UpdateEngine {
    private static final Comparator<UpdateEdit> EDIT_ORDER = Comparator
            .comparingInt((UpdateEdit edit) -> edit.surface() == OutdatedSurface.VERSION_ALIAS ? 0 : 1)
            .thenComparing(UpdateEdit::identifier)
            .thenComparing(UpdateEdit::section);

    private final SurfaceDiscovery surfaceDiscovery;
    private final RepositoryAccessPlanner planner;
    private final UpdateTargetCatalog catalog = new UpdateTargetCatalog();
    private final VersionClassifier classifier = new VersionClassifier();
    private final UpdateApplier applier = new UpdateApplier();

    public UpdateEngine(VersionDiscovery discovery) {
        this(discovery, new RepositoryAccessPlanner());
    }

    public UpdateEngine(VersionDiscovery discovery, RepositoryAccessPlanner planner) {
        this.surfaceDiscovery = new SurfaceDiscovery(discovery);
        this.planner = planner;
    }

    public UpdatePlan plan(UpdatePlanningScope scope, UpdateOptions options) {
        List<RepositoryAccess> repositories = planner.plan(scope.discovery());
        Map<String, MetadataDiscovery> memo = new LinkedHashMap<>();
        List<UpdateEdit> edits = new ArrayList<>();
        List<UpdateSkip> skips = new ArrayList<>();
        for (UpdateTargetCatalog.Entry entry : catalog.entries(
                scope.manifest(),
                scope.manifestPath(),
                scope.lockfilePath())) {
            SurfaceRequest surface = entry.request();
            if (!Selectors.matches(surface.identifier(), surface.section(), surface.surface().jsonName(), options.selectors())) {
                continue;
            }
            MetadataDiscovery discovered = surfaceDiscovery.discover(surface, repositories, options.offline(), memo);
            if (!discovered.resolved()) {
                continue;
            }
            VersionCandidates candidates =
                    classifier.candidates(surface.currentVersion(), discovered.versions(), options.includePrereleases());
            Optional<String> target = options.ceiling().target(candidates);
            if (target.isEmpty() || target.orElseThrow().equals(surface.currentVersion())) {
                continue;
            }
            recordChange(entry, surface, target.orElseThrow(), edits, skips);
        }
        edits.sort(EDIT_ORDER);
        return new UpdatePlan(edits, skips, aliasFanOutWarnings(edits));
    }

    private void recordChange(
            UpdateTargetCatalog.Entry updateTarget,
            SurfaceRequest surface,
            String target,
            List<UpdateEdit> edits,
            List<UpdateSkip> skips) {
        if (!updateTarget.updateable()) {
            skips.add(new UpdateSkip(
                    surface.surface(),
                    surface.identifier(),
                    surface.section(),
                    updateTarget.updateBlocker().orElseThrow()));
            return;
        }
        edits.add(new UpdateEdit(
                surface.surface(),
                surface.identifier(),
                surface.section(),
                surface.currentVersion(),
                target,
                classifier.classify(surface.currentVersion(), target),
                surface.governs()));
    }

    public AuthoredManifest apply(AuthoredManifest manifest, UpdatePlan plan) {
        return applier.apply(manifest, plan);
    }

    private static List<String> aliasFanOutWarnings(List<UpdateEdit> edits) {
        List<String> warnings = new ArrayList<>();
        for (UpdateEdit edit : edits) {
            if (edit.surface() == OutdatedSurface.VERSION_ALIAS) {
                warnings.add("Alias `" + edit.identifier() + "` " + edit.fromVersion() + " -> " + edit.toVersion()
                        + " updates " + edit.fanOut().size() + " referencing coordinate(s): "
                        + String.join(", ", edit.fanOut()) + ".");
            }
        }
        return warnings;
    }
}
