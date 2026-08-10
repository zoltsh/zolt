package sh.zolt.update;

import sh.zolt.dependency.VersionCandidates;
import sh.zolt.dependency.VersionClassifier;
import sh.zolt.maven.metadata.MetadataDiscovery;
import sh.zolt.maven.metadata.VersionDiscovery;
import sh.zolt.maven.repository.RepositoryAccess;
import sh.zolt.maven.repository.RepositoryAccessPlanner;
import sh.zolt.project.ProjectConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plans and applies dependency-version updates. Planning discovers candidates per surface, picks the
 * target at the requested ceiling, and records applicable changes as edits and unsupported ones as
 * skips. Applying uses ONLY existing mutation machinery — {@code withVersionAliases} for aliases,
 * {@code ProjectConfigDependencyMutator} for literal dependencies and platforms, and a
 * kind/reason-preserving rebuild for constraints — never writing a literal over a versionRef.
 */
public final class UpdateEngine {
    private static final Comparator<UpdateEdit> EDIT_ORDER = Comparator
            .comparingInt((UpdateEdit edit) -> edit.surface() == OutdatedSurface.VERSION_ALIAS ? 0 : 1)
            .thenComparing(UpdateEdit::identifier)
            .thenComparing(UpdateEdit::section);

    private final SurfaceDiscovery surfaceDiscovery;
    private final RepositoryAccessPlanner planner;
    private final SurfaceCollector collector = new SurfaceCollector();
    private final VersionClassifier classifier = new VersionClassifier();
    private final UpdateApplier applier = new UpdateApplier();

    public UpdateEngine(VersionDiscovery discovery) {
        this(discovery, new RepositoryAccessPlanner());
    }

    public UpdateEngine(VersionDiscovery discovery, RepositoryAccessPlanner planner) {
        this.surfaceDiscovery = new SurfaceDiscovery(discovery);
        this.planner = planner;
    }

    public UpdatePlan plan(ProjectConfig config, UpdateOptions options) {
        List<RepositoryAccess> repositories = planner.plan(config);
        Map<String, MetadataDiscovery> memo = new LinkedHashMap<>();
        List<UpdateEdit> edits = new ArrayList<>();
        List<UpdateSkip> skips = new ArrayList<>();
        for (SurfaceRequest surface : collector.collect(config)) {
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
            recordChange(surface, target.orElseThrow(), edits, skips);
        }
        edits.sort(EDIT_ORDER);
        return new UpdatePlan(edits, skips, aliasFanOutWarnings(edits));
    }

    private void recordChange(SurfaceRequest surface, String target, List<UpdateEdit> edits, List<UpdateSkip> skips) {
        if (!UpdateApplicability.isApplicable(surface.surface())) {
            skips.add(new UpdateSkip(
                    surface.surface(),
                    surface.identifier(),
                    surface.section(),
                    UpdateApplicability.reason(surface.surface())));
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

    public ProjectConfig apply(ProjectConfig config, UpdatePlan plan) {
        return applier.apply(config, plan);
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
