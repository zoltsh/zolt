package sh.zolt.resolve.traversal;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.RawPomDependency;
import sh.zolt.project.DependencyConstraint;
import sh.zolt.project.VersionPolicy;
import sh.zolt.resolve.DependencyPolicyEffect;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolutionVariant;
import sh.zolt.resolve.SnapshotAllowance;
import sh.zolt.resolve.metadata.platform.ManagedVersion;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.graph.PackageNode;
import sh.zolt.resolve.request.RequestOrigin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class DependencyTraversalCandidateSelector {
    private final DependencyTraversalPolicy traversalPolicy;
    private final DependencyTransitiveScopeSelector transitiveScopeSelector;
    private final List<DependencyGlobalExclusion> globalExclusions;
    private final Map<PackageId, DependencyConstraint> strictConstraints;
    private final Map<PackageId, ManagedVersion> rootManagedVersions;
    private final String retryCommand;
    private final SnapshotAllowance snapshotAllowance;
    private final Map<ResolutionVariant, String> versionOverrides;
    private final Map<ResolutionVariant, String> workspaceVersionOverrides;

    DependencyTraversalCandidateSelector(
            DependencyTraversalPolicy traversalPolicy,
            DependencyTransitiveScopeSelector transitiveScopeSelector,
            List<DependencyGlobalExclusion> globalExclusions,
            Map<PackageId, DependencyConstraint> strictConstraints,
            Map<PackageId, ManagedVersion> rootManagedVersions,
            String retryCommand,
            SnapshotAllowance snapshotAllowance) {
        this(
                traversalPolicy,
                transitiveScopeSelector,
                globalExclusions,
                strictConstraints,
                rootManagedVersions,
                retryCommand,
                snapshotAllowance,
                Map.of(),
                Map.of());
    }

    DependencyTraversalCandidateSelector(
            DependencyTraversalPolicy traversalPolicy,
            DependencyTransitiveScopeSelector transitiveScopeSelector,
            List<DependencyGlobalExclusion> globalExclusions,
            Map<PackageId, DependencyConstraint> strictConstraints,
            Map<PackageId, ManagedVersion> rootManagedVersions,
            String retryCommand,
            SnapshotAllowance snapshotAllowance,
            Map<ResolutionVariant, String> versionOverrides) {
        this(
                traversalPolicy,
                transitiveScopeSelector,
                globalExclusions,
                strictConstraints,
                rootManagedVersions,
                retryCommand,
                snapshotAllowance,
                versionOverrides,
                versionOverrides);
    }

    DependencyTraversalCandidateSelector(
            DependencyTraversalPolicy traversalPolicy,
            DependencyTransitiveScopeSelector transitiveScopeSelector,
            List<DependencyGlobalExclusion> globalExclusions,
            Map<PackageId, DependencyConstraint> strictConstraints,
            Map<PackageId, ManagedVersion> rootManagedVersions,
            String retryCommand,
            SnapshotAllowance snapshotAllowance,
            Map<ResolutionVariant, String> versionOverrides,
            Map<ResolutionVariant, String> workspaceVersionOverrides) {
        this.traversalPolicy = traversalPolicy;
        this.transitiveScopeSelector = transitiveScopeSelector;
        this.globalExclusions = List.copyOf(globalExclusions);
        this.strictConstraints = Map.copyOf(strictConstraints);
        this.rootManagedVersions = Map.copyOf(rootManagedVersions);
        this.retryCommand = retryCommand == null || retryCommand.isBlank() ? "zolt resolve" : retryCommand.trim();
        this.snapshotAllowance = snapshotAllowance == null ? SnapshotAllowance.none() : snapshotAllowance;
        this.versionOverrides = versionOverrides == null ? Map.of() : Map.copyOf(versionOverrides);
        this.workspaceVersionOverrides =
                workspaceVersionOverrides == null ? Map.of() : Map.copyOf(workspaceVersionOverrides);
    }

    DependencyTraversalSelection select(DependencyTraversalCandidate candidate) {
        NormalizedDependency dependency = candidate.dependency();
        Coordinate coordinate = coordinate(dependency.rawDependency());
        List<DependencyPolicyEffect> exclusionEffects = exclusionEffects(candidate, coordinate);
        if (!exclusionEffects.isEmpty()) {
            return DependencyTraversalSelection.skippedWithEffects(exclusionEffects);
        }

        DependencyTraversalDecision decision = traversalPolicy.decide(dependency, false);
        if (!decision.included()) {
            return DependencyTraversalSelection.skipped();
        }
        Optional<DependencyScope> requestScope = transitiveScopeSelector.select(
                candidate.item().request().scope(),
                dependency.scope());
        if (requestScope.isEmpty()) {
            return DependencyTraversalSelection.skipped();
        }

        PackageId packageId = new PackageId(
                dependency.rawDependency().groupId(),
                dependency.rawDependency().artifactId());
        DependencyConstraint constraint = strictConstraints.get(packageId);
        ManagedVersion managedVersion = managedJarDependency(dependency.rawDependency())
                ? rootManagedVersions.get(packageId)
                : null;
        Optional<String> originalRequestedVersion = dependency.rawDependency().version();
        String policySelectedVersion = requestedVersion(
                candidate.source(),
                dependency,
                packageId,
                constraint,
                managedVersion);
        ResolutionVariant variant = new ResolutionVariant(
                packageId,
                new LockArtifactVariant(
                        dependency.rawDependency().type().orElse("jar"),
                        dependency.rawDependency().classifier()));
        String workspaceVersion = workspaceVersionOverrides.get(variant);
        String requestedVersion = policySelectedVersion;
        String materializedVersion = workspaceVersion == null
                ? versionOverrides.getOrDefault(variant, requestedVersion)
                : workspaceVersion;
        boolean workspaceMediated = workspaceVersion != null
                && !workspaceVersion.equals(policySelectedVersion);
        validateSupportedTransitiveVersion(packageId, requestedVersion, candidate.source());
        List<DependencyPolicyEffect> policyEffects = new ArrayList<>();
        if (workspaceMediated) {
            policyEffects.add(DependencyTraversalPolicyEffects.workspaceMediation(
                    packageId,
                    policySelectedVersion,
                    materializedVersion,
                    candidate.source()));
        } else if (constraint != null) {
            policyEffects.add(DependencyTraversalPolicyEffects.strictVersion(
                    packageId,
                    originalRequestedVersion,
                    candidate.source(),
                    constraint));
        } else if (managedVersion != null && managedVersionParticipates(originalRequestedVersion, managedVersion)) {
            policyEffects.add(DependencyTraversalPolicyEffects.managedVersion(
                    packageId,
                    originalRequestedVersion,
                    candidate.source(),
                    managedVersion));
        }
        DependencyRequest request = new DependencyRequest(
                packageId,
                requestedVersion,
                requestScope.orElseThrow(),
                RequestOrigin.TRANSITIVE,
                artifactDescriptor(packageId, requestedVersion, dependency.rawDependency()));
        return DependencyTraversalSelection.selected(
                DependencyTraversalItem.transitive(
                        candidate.source(),
                        request,
                        materializedVersion,
                        candidate.item().request().scope(),
                        candidate.item().including(dependency.exclusions()),
                        candidate.item().optionalRoot(),
                        decision),
                policyEffects);
    }

    private String requestedVersion(
            PackageNode source,
            NormalizedDependency dependency,
            PackageId packageId,
            DependencyConstraint constraint,
            ManagedVersion managedVersion) {
        if (constraint != null) {
            return constraint.version();
        }
        if (managedVersion != null) {
            return managedVersion.version();
        }
        return dependency.rawDependency().version()
                .orElseThrow(() -> new GraphTraversalException(
                        "Dependency "
                                + dependency.rawDependency().groupId()
                                + ":"
                                + dependency.rawDependency().artifactId()
                                + " from "
                                + source.packageId()
                                + ":"
                                + source.selectedVersion()
                                + " does not declare or inherit a version."));
    }

    private List<DependencyPolicyEffect> exclusionEffects(
            DependencyTraversalCandidate candidate,
            Coordinate coordinate) {
        List<DependencyPolicyEffect> effects = new ArrayList<>();
        candidate.item().matchingExclusions(coordinate).stream()
                .map(exclusion -> new DependencyPolicyEffect(
                        "edge-exclusion",
                        new PackageId(coordinate.groupId(), coordinate.artifactId()),
                        candidate.dependency().rawDependency().version(),
                        Optional.of(sourceCoordinate(candidate.source())),
                        "dependency edge exclusion from "
                                + sourceCoordinate(candidate.source())
                                + " to "
                                + coordinate.groupId()
                                + ":"
                                + coordinate.artifactId()))
                .forEach(effects::add);
        matchingGlobalExclusions(coordinate).stream()
                .map(exclusion -> new DependencyPolicyEffect(
                        "global-exclusion",
                        new PackageId(coordinate.groupId(), coordinate.artifactId()),
                        candidate.dependency().rawDependency().version(),
                        Optional.of(sourceCoordinate(candidate.source())),
                        exclusion.reason()
                                .map(reason -> "[dependencies.policy].deny "
                                        + coordinate.groupId()
                                        + ":"
                                        + coordinate.artifactId()
                                        + " ("
                                        + reason
                                        + ")")
                                .orElse("[dependencies.policy].deny "
                                        + coordinate.groupId()
                                        + ":"
                                        + coordinate.artifactId())))
                .forEach(effects::add);
        return effects;
    }

    private List<DependencyGlobalExclusion> matchingGlobalExclusions(Coordinate coordinate) {
        return globalExclusions.stream()
                .filter(exclusion -> exclusion.exclusion().matches(coordinate))
                .toList();
    }

    private static Optional<ArtifactDescriptor> artifactDescriptor(
            PackageId packageId,
            String version,
            RawPomDependency dependency) {
        String extension = dependency.type().orElse("jar");
        if (dependency.classifier().isEmpty() && "jar".equals(extension)) {
            return Optional.empty();
        }
        Coordinate coordinate = new Coordinate(packageId.groupId(), packageId.artifactId(), Optional.of(version));
        return Optional.of(new ArtifactDescriptor(coordinate, dependency.classifier(), extension));
    }

    private static Coordinate coordinate(RawPomDependency dependency) {
        return new Coordinate(dependency.groupId(), dependency.artifactId(), dependency.version());
    }

    private static boolean managedJarDependency(RawPomDependency dependency) {
        return "jar".equals(dependency.type().orElse("jar")) && dependency.classifier().isEmpty();
    }

    private static boolean managedVersionParticipates(
            Optional<String> originalRequestedVersion,
            ManagedVersion managedVersion) {
        return originalRequestedVersion
                .map(version -> !version.equals(managedVersion.version()))
                .orElse(true);
    }

    private void validateSupportedTransitiveVersion(
            PackageId packageId,
            String requestedVersion,
            PackageNode source) {
        boolean snapshotPermitted = snapshotAllowance.permitsSnapshot(packageId, requestedVersion);
        VersionPolicy.violation(VersionPolicy.Context.EXTERNAL_DEPENDENCY, requestedVersion, snapshotPermitted)
                .ifPresent(violation -> {
                    if (VersionPolicy.isSnapshot(requestedVersion)) {
                        throw ResolveException.actionable(
                                "Unsupported SNAPSHOT dependency version `"
                                        + requestedVersion
                                        + "` for `"
                                        + packageId
                                        + "` required by `"
                                        + sourceCoordinate(source)
                                        + "`. "
                                        + SnapshotAllowance.SUPPORTED_SUBSET,
                                snapshotAllowance.snapshotRemediation(
                                        packageId + ":" + requestedVersion, retryCommand));
                    }
                    throw ResolveException.actionable(
                            "Unsupported transitive dependency version `"
                                    + requestedVersion
                                    + "` for `"
                                    + packageId
                                    + "` required by `"
                                    + sourceCoordinate(source)
                                    + "`.",
                            violation.guidance()
                                    + " Add [dependencies.constraints] entry `\""
                                    + packageId
                                    + "\" = { version = \""
                                    + fixedVersionExample(requestedVersion)
                                    + "\", kind = \"strict\" }`, then run `"
                                    + retryCommand
                                    + "` again.");
                });
    }

    private static String fixedVersionExample(String requestedVersion) {
        if (requestedVersion == null || requestedVersion.length() < 3) {
            return "1.0.0";
        }
        int comma = requestedVersion.indexOf(',');
        if ((requestedVersion.startsWith("[") || requestedVersion.startsWith("(")) && comma > 1) {
            String lowerBound = requestedVersion.substring(1, comma).trim();
            if (!lowerBound.isBlank()) {
                return lowerBound;
            }
        }
        return "1.0.0";
    }

    private static String sourceCoordinate(PackageNode node) {
        return node.packageId() + ":" + node.selectedVersion();
    }
}
