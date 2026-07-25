package sh.zolt.workspace.resolve;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.dependency.VersionComparator;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockDependencyEdge;
import sh.zolt.lockfile.LockMemberGraph;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.resolve.ResolutionVariant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class WorkspaceExternalPackageSelector {
    private static final VersionComparator VERSION_COMPARATOR = new VersionComparator();

    Map<ResolutionVariant, String> versionOverrides(List<LockPackage> candidates) {
        Map<PackageVariantKey, List<LockPackage>> candidatesByVariant =
                candidatesByVariant(candidates.stream()
                        .filter(candidate -> candidate.scope() != DependencyScope.TOOL_EXEC)
                        .toList());
        Map<ResolutionVariant, String> overrides = new LinkedHashMap<>();
        for (Map.Entry<PackageVariantKey, List<LockPackage>> entry : candidatesByVariant.entrySet()) {
            overrides.put(
                    new ResolutionVariant(entry.getKey().packageId(), entry.getKey().variant()),
                    selectVersion(entry.getValue()).version());
        }
        return Map.copyOf(overrides);
    }

    List<LockConflict> versionConflicts(List<LockPackage> candidates) {
        Map<PackageVariantKey, List<LockPackage>> candidatesByVariant =
                candidatesByVariant(candidates.stream()
                        .filter(candidate -> candidate.scope() != DependencyScope.TOOL_EXEC)
                        .toList());
        List<LockConflict> conflicts = new ArrayList<>();
        for (Map.Entry<PackageVariantKey, List<LockPackage>> entry : candidatesByVariant.entrySet()) {
            PackageVariantKey key = entry.getKey();
            List<String> requestedVersions = entry.getValue().stream()
                    .map(LockPackage::version)
                    .distinct()
                    .sorted(VERSION_COMPARATOR.thenComparing(Comparator.naturalOrder()))
                    .toList();
            if (requestedVersions.size() > 1) {
                WorkspaceExternalSelection.VersionSelection selection = selectVersion(entry.getValue());
                conflicts.add(new LockConflict(
                        key.packageId(),
                        selection.version(),
                        requestedVersions,
                        selection.reason(),
                        Optional.empty(),
                        Optional.of(key.variant())));
            }
        }
        return List.copyOf(conflicts);
    }

    WorkspaceExternalSelection select(List<LockPackage> candidates) {
        return select(candidates, false);
    }

    WorkspaceExternalSelection selectMaterialized(List<LockPackage> candidates) {
        return select(candidates, true);
    }

    private WorkspaceExternalSelection select(
            List<LockPackage> candidates,
            boolean requireMaterializedMembers) {
        List<LockPackage> regularCandidates = candidates.stream()
                .filter(candidate -> candidate.scope() != DependencyScope.TOOL_EXEC)
                .toList();
        List<LockPackage> execCandidates = candidates.stream()
                .filter(candidate -> candidate.scope() == DependencyScope.TOOL_EXEC)
                .toList();

        // Two variants of one GAV (a plain jar and a linux-x86_64 classified jar, or a jar and a .zip)
        // are distinct artifacts, so each gets its OWN lane keyed by (PackageId, variant). Everything the
        // workspace layer mediates — the selected version, the edge-version rewrite, and the conflict
        // record — runs WITHIN a lane, so same-GA different-variant entries coexist at their own mediated
        // versions and never collapse onto (or falsely conflict with) each other.
        Map<PackageVariantKey, List<LockPackage>> candidatesByVariant = candidatesByVariant(regularCandidates);

        Map<PackageVariantKey, WorkspaceExternalSelection.VersionSelection> selections = new LinkedHashMap<>();
        Map<PackageVariantKey, String> selectedVersionByVariant = new LinkedHashMap<>();
        for (Map.Entry<PackageVariantKey, List<LockPackage>> entry : candidatesByVariant.entrySet()) {
            WorkspaceExternalSelection.VersionSelection selection = selectVersion(entry.getValue());
            selections.put(entry.getKey(), selection);
            selectedVersionByVariant.put(entry.getKey(), selection.version());
        }

        List<LockPackage> packages = new ArrayList<>();
        List<LockMemberGraph> memberGraphs = new ArrayList<>();
        for (Map.Entry<PackageVariantKey, List<LockPackage>> entry : candidatesByVariant.entrySet()) {
            List<LockPackage> variantCandidates = entry.getValue();
            String variantSelectedVersion = selections.get(entry.getKey()).version();
            WorkspaceArtifactIdentityVerifier.requireIdenticalBytes(
                    variantCandidates.stream()
                            .filter(candidate ->
                                    candidate.version().equals(variantSelectedVersion))
                            .toList());
            List<DependencyScope> scopes = variantCandidates.stream()
                    .map(LockPackage::scope)
                    .distinct()
                    .sorted(Comparator.comparing(DependencyScope::lockfileName))
                    .toList();
            for (DependencyScope scope : scopes) {
                SelectedPackage selected = selectedPackage(
                        variantCandidates,
                        variantSelectedVersion,
                        scope,
                        selectedVersionByVariant,
                        requireMaterializedMembers);
                packages.add(selected.lockPackage());
                memberGraphs.addAll(selected.memberGraphs());
            }
        }

        List<LockConflict> conflicts = new ArrayList<>(versionConflicts(regularCandidates));
        packages.addAll(WorkspaceExecPackageSelector.select(execCandidates));
        return new WorkspaceExternalSelection(packages, conflicts, memberGraphs);
    }

    private static Map<PackageVariantKey, List<LockPackage>> candidatesByVariant(
            List<LockPackage> regularCandidates) {
        Map<PackageVariantKey, List<LockPackage>> candidatesByVariant = new LinkedHashMap<>();
        regularCandidates.stream()
                .sorted(Comparator.comparing(lockPackage -> lockPackage.packageId()
                        + ":"
                        + lockPackage.version()
                        + ":"
                        + lockPackage.scope().lockfileName()
                        + ":"
                        + LockArtifactVariant.of(lockPackage).key()))
                .forEach(lockPackage -> candidatesByVariant
                        .computeIfAbsent(
                                new PackageVariantKey(lockPackage.packageId(), LockArtifactVariant.of(lockPackage)),
                                ignored -> new ArrayList<>())
                        .add(lockPackage));
        return candidatesByVariant;
    }

    /** Identity of one aggregation lane: a {@link PackageId} plus its artifact variant. */
    private record PackageVariantKey(PackageId packageId, LockArtifactVariant variant) {
    }

    private static WorkspaceExternalSelection.VersionSelection selectVersion(List<LockPackage> candidates) {
        List<LockPackage> directCandidates = candidates.stream()
                .filter(LockPackage::direct)
                .toList();
        if (!directCandidates.isEmpty()) {
            return new WorkspaceExternalSelection.VersionSelection(
                    newestVersion(directCandidates),
                    ConflictSelectionReason.DIRECT_DEPENDENCY);
        }
        return new WorkspaceExternalSelection.VersionSelection(
                newestVersion(candidates),
                ConflictSelectionReason.NEWEST_VERSION);
    }

    private static String newestVersion(List<LockPackage> candidates) {
        return candidates.stream()
                .map(LockPackage::version)
                .max(VERSION_COMPARATOR)
                .orElseThrow();
    }

    private static SelectedPackage selectedPackage(
            List<LockPackage> packageCandidates,
            String selectedVersion,
            DependencyScope scope,
            Map<PackageVariantKey, String> selectedVersionByVariant,
            boolean requireMaterializedMembers) {
        List<LockPackage> selectedCandidates = packageCandidates.stream()
                .filter(lockPackage -> lockPackage.version().equals(selectedVersion))
                .filter(lockPackage -> lockPackage.scope() == scope)
                .sorted(Comparator.comparing(WorkspaceExternalPackageSelector::templateSortKey))
                .toList();
        if (selectedCandidates.isEmpty()) {
            throw new IllegalStateException(
                        "Workspace mediation selected "
                                + packageCandidates.getFirst().packageId()
                                + ":"
                                + selectedVersion
                                + " for "
                                + scope.lockfileName()
                                + " without resolving that version in the scope.");
        }
        WorkspaceArtifactIdentityVerifier.requireIdenticalBytes(selectedCandidates);
        LockPackage selectedTemplate = selectedCandidates.getFirst();
        List<LockPackage> scopeCandidates = packageCandidates.stream()
                .filter(lockPackage -> lockPackage.scope() == scope)
                .toList();
        if (requireMaterializedMembers) {
            requireSelectedVersionForEveryMember(
                    scopeCandidates, selectedCandidates, selectedVersion, scope);
        }
        boolean direct = scopeCandidates.stream().anyMatch(LockPackage::direct);
        Set<String> members = new LinkedHashSet<>();
        Set<String> exportedBy = new LinkedHashSet<>();
        Set<String> dependencies = new LinkedHashSet<>();
        Set<String> policies = new LinkedHashSet<>();
        List<LockMemberGraph> memberGraphs = new ArrayList<>();
        for (LockPackage candidate : scopeCandidates) {
            members.addAll(candidate.members());
            exportedBy.addAll(candidate.exportedBy());
        }
        for (LockPackage candidate : selectedCandidates) {
            List<String> rewrittenDependencies =
                    rewriteDependencies(candidate.dependencies(), selectedVersionByVariant);
            dependencies.addAll(rewrittenDependencies);
            policies.addAll(candidate.policies());
            for (String member : candidate.members()) {
                memberGraphs.add(new LockMemberGraph(
                        member,
                        candidate.packageId(),
                        selectedVersion,
                        LockArtifactVariant.of(candidate),
                        scope,
                        rewrittenDependencies,
                        candidate.policies()));
            }
        }
        LockPackage lockPackage = new LockPackage(
                selectedTemplate.packageId(),
                selectedVersion,
                selectedTemplate.source(),
                scope,
                direct,
                selectedTemplate.jar(),
                selectedTemplate.pom(),
                selectedTemplate.jarSha256(),
                selectedTemplate.pomSha256(),
                selectedTemplate.artifact(),
                selectedTemplate.artifactType(),
                selectedTemplate.artifactSha256(),
                selectedTemplate.workspace(),
                selectedTemplate.workspaceOutput(),
                dependencies.stream().sorted().toList(),
                members.stream().sorted().toList(),
                exportedBy.stream().sorted().toList(),
                policies.stream().sorted().toList(),
                List.of());
        boolean memberViewsDiffer = memberGraphs.stream()
                .map(graph -> new MemberGraphFacts(
                        graph.dependencies(), graph.policies()))
                .distinct()
                .count() > 1;
        return new SelectedPackage(
                lockPackage,
                memberViewsDiffer ? memberGraphs : List.of());
    }

    private static void requireSelectedVersionForEveryMember(
            List<LockPackage> scopeCandidates,
            List<LockPackage> selectedCandidates,
            String selectedVersion,
            DependencyScope scope) {
        Set<String> participating = new LinkedHashSet<>();
        scopeCandidates.forEach(candidate -> participating.addAll(candidate.members()));
        Set<String> materialized = new LinkedHashSet<>();
        selectedCandidates.forEach(candidate -> materialized.addAll(candidate.members()));
        participating.removeAll(materialized);
        if (!participating.isEmpty()) {
            throw new IllegalStateException(
                    "Workspace mediation selected version "
                            + selectedVersion
                            + " in scope "
                            + scope.lockfileName()
                            + " without materializing it for members "
                            + participating
                            + ".");
        }
    }

    private static String templateSortKey(LockPackage lockPackage) {
        return lockPackage.source()
                + ":"
                + lockPackage.jar().orElse("")
                + ":"
                + lockPackage.artifact().orElse("")
                + ":"
                + String.join(",", lockPackage.members());
    }

    private record SelectedPackage(
            LockPackage lockPackage,
            List<LockMemberGraph> memberGraphs) {
        SelectedPackage {
            memberGraphs = List.copyOf(memberGraphs);
        }
    }

    private record MemberGraphFacts(
            List<String> dependencies,
            List<String> policies) {
    }

    private static List<String> rewriteDependencies(
            List<String> dependencies,
            Map<PackageVariantKey, String> selectedVersionByVariant) {
        return dependencies.stream()
                .map(dependency -> rewriteDependency(dependency, selectedVersionByVariant))
                .sorted()
                .toList();
    }

    /**
     * Rewrites one dependency edge to its target's cross-member mediated version, staying within the
     * target's variant lane and preserving the variant qualifier. A classified target edge mediates
     * against the classified lane, never against the plain-jar lane at the same GA. An unparseable or
     * unmediated edge is returned untouched, matching the prior tolerant behavior.
     */
    private static String rewriteDependency(
            String dependency, Map<PackageVariantKey, String> selectedVersionByVariant) {
        Optional<LockDependencyEdge> parsed = LockDependencyEdge.parse(dependency);
        if (parsed.isEmpty()) {
            return dependency;
        }
        LockDependencyEdge edge = parsed.orElseThrow();
        String selectedVersion = selectedVersionByVariant.get(new PackageVariantKey(edge.packageId(), edge.variant()));
        if (selectedVersion == null) {
            return dependency;
        }
        return edge.scope()
                .map(scope -> LockDependencyEdge.encode(
                        edge.packageId(), selectedVersion, edge.variant(), scope))
                .orElseGet(() -> LockDependencyEdge.encode(
                        edge.packageId(), selectedVersion, edge.variant()));
    }
}
