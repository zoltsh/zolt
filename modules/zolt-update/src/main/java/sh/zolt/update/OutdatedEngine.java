package sh.zolt.update;

import sh.zolt.dependency.VersionCandidates;
import sh.zolt.dependency.VersionClassifier;
import sh.zolt.maven.metadata.MetadataDiscovery;
import sh.zolt.maven.metadata.VersionDiscovery;
import sh.zolt.maven.repository.RepositoryAccess;
import sh.zolt.maven.repository.RepositoryAccessPlanner;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Computes an outdated report for one or more scopes: enumerates every version surface, discovers
 * available versions (advisory-only), classifies update targets, applies selector and status
 * filters, orders deterministically (aliases first, then group:artifact), and — across a workspace —
 * annotates coordinates shared by multiple members.
 */
public final class OutdatedEngine {
    private static final Comparator<OutdatedEntry> ENTRY_ORDER = Comparator
            .comparingInt((OutdatedEntry entry) -> entry.surface() == OutdatedSurface.VERSION_ALIAS ? 0 : 1)
            .thenComparing(OutdatedEntry::identifier)
            .thenComparing(OutdatedEntry::section);

    private final SurfaceDiscovery surfaceDiscovery;
    private final RepositoryAccessPlanner planner;
    private final UpdateTargetCatalog catalog = new UpdateTargetCatalog();
    private final VersionClassifier classifier = new VersionClassifier();

    public OutdatedEngine(VersionDiscovery discovery) {
        this(discovery, new RepositoryAccessPlanner());
    }

    public OutdatedEngine(VersionDiscovery discovery, RepositoryAccessPlanner planner) {
        this.surfaceDiscovery = new SurfaceDiscovery(discovery);
        this.planner = planner;
    }

    public OutdatedReport report(List<OutdatedScope> scopes, OutdatedOptions options) {
        List<OutdatedScopeReport> scopeReports = new ArrayList<>();
        for (OutdatedScope scope : scopes) {
            scopeReports.add(reportScope(scope, options));
        }
        return applyWorkspaceDedup(scopeReports);
    }

    private OutdatedScopeReport reportScope(OutdatedScope scope, OutdatedOptions options) {
        List<RepositoryAccess> repositories = planner.plan(scope.discovery());
        List<UpdateTargetCatalog.Entry> catalogEntries = catalog.entries(
                scope.manifest(), scope.manifestPath(), scope.lockfilePath(), scope.aliasReferenceScopes());
        Map<String, MetadataDiscovery> memo = new LinkedHashMap<>();
        List<OutdatedEntry> entries = new ArrayList<>();
        for (UpdateTargetCatalog.Entry catalogEntry : catalogEntries) {
            OutdatedEntry entry = evaluate(catalogEntry, repositories, memo, options);
            if (include(entry, options)) {
                entries.add(entry);
            }
        }
        entries.sort(ENTRY_ORDER);
        return new OutdatedScopeReport(scope.label(), scope.manifestPath(), scope.lockfilePath(), entries);
    }

    private OutdatedEntry evaluate(
            UpdateTargetCatalog.Entry catalogEntry,
            List<RepositoryAccess> repositories,
            Map<String, MetadataDiscovery> memo,
            OutdatedOptions options) {
        SurfaceRequest surface = catalogEntry.request();
        MetadataDiscovery discovered =
                surfaceDiscovery.discover(surface, repositories, options.offline(), memo);
        if (!discovered.resolved()) {
            return entry(
                    catalogEntry,
                    OutdatedStatus.UNKNOWN,
                    OutdatedCandidates.none(),
                    Optional.empty(),
                    discovered.notes());
        }
        VersionCandidates candidates =
                classifier.candidates(surface.currentVersion(), discovered.versions(), options.includePrereleases());
        OutdatedStatus status = candidates.updateAvailable() ? OutdatedStatus.UPDATE_AVAILABLE : OutdatedStatus.CURRENT;
        Optional<String> source = candidates.selectedLatest().flatMap(discovered::source);
        return entry(
                catalogEntry,
                status,
                toCandidates(surface.currentVersion(), candidates),
                source,
                discovered.notes());
    }

    private OutdatedCandidates toCandidates(String current, VersionCandidates candidates) {
        return new OutdatedCandidates(
                candidates.latestPatch(),
                candidates.latestMinor(),
                candidates.latestMajor(),
                candidates.selectedInMajor(),
                candidates.selectedInMajor().map(version -> classifier.classify(current, version)),
                candidates.selectedLatest(),
                candidates.selectedLatest().map(version -> classifier.classify(current, version)));
    }

    private static OutdatedEntry entry(
            UpdateTargetCatalog.Entry target,
            OutdatedStatus status,
            OutdatedCandidates candidates,
            Optional<String> source,
            List<String> notes) {
        return new OutdatedEntry(
                target,
                status,
                candidates,
                source,
                List.of(),
                notes);
    }

    private static boolean include(OutdatedEntry entry, OutdatedOptions options) {
        if (!options.includeUpToDate() && entry.status() == OutdatedStatus.CURRENT) {
            return false;
        }
        return Selectors.matches(entry.identifier(), entry.section(), entry.surface().jsonName(), options.selectors());
    }

    private OutdatedReport applyWorkspaceDedup(List<OutdatedScopeReport> scopeReports) {
        if (scopeReports.size() <= 1) {
            return new OutdatedReport(scopeReports, List.of());
        }
        Map<String, List<String>> membersByIdentifier = new TreeMap<>();
        for (OutdatedScopeReport scope : scopeReports) {
            for (OutdatedEntry entry : scope.entries()) {
                membersByIdentifier
                        .computeIfAbsent(entry.identifier(), ignored -> new ArrayList<>())
                        .add(scope.label());
            }
        }
        List<OutdatedScopeReport> annotated = new ArrayList<>();
        for (OutdatedScopeReport scope : scopeReports) {
            List<OutdatedEntry> entries = new ArrayList<>();
            for (OutdatedEntry entry : scope.entries()) {
                List<String> members = membersByIdentifier.get(entry.identifier());
                entries.add(members.size() > 1 ? entry.withMembers(List.copyOf(members)) : entry);
            }
            annotated.add(new OutdatedScopeReport(
                    scope.label(), scope.manifestPath(), scope.lockfilePath(), entries));
        }
        return new OutdatedReport(annotated, sharedNotes(membersByIdentifier));
    }

    private static List<String> sharedNotes(Map<String, List<String>> membersByIdentifier) {
        List<String> notes = new ArrayList<>();
        membersByIdentifier.forEach((identifier, members) -> {
            if (members.size() > 1) {
                notes.add(identifier + " is shared by members " + String.join(", ", members) + ".");
            }
        });
        return notes;
    }
}
