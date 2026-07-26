package sh.zolt.workspace.resolve;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockMemberGraph;
import sh.zolt.lockfile.LockMemberGraphIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.DependencyPolicyExclusion;
import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceProjectEdge;

/**
 * Projects an aggregate workspace lock into one member's all-scope dependency-policy view.
 *
 * <p>The aggregate lock deliberately collapses packages shared by several members. Its {@code direct},
 * dependency, and policy fields are therefore aggregate facts. Quality and policy consumers need the
 * opposite shape: only packages attributed to one member, with directness reconstructed from that
 * member's effective config and graph facts restored from {@code [[memberGraph]]}. This projection
 * retains compile, runtime, provided, dev, test, and processor lanes, including their exact artifact
 * variants and scopes.
 */
public final class WorkspaceMemberPolicyLockProjection {
    public ZoltLockfile project(
            String memberPath,
            ProjectConfig effectiveConfig,
            ZoltLockfile aggregate,
            Workspace workspace) {
        LockMemberGraphIndex graphIndex = new LockMemberGraphIndex(
                aggregate.memberGraphs(), aggregate.packages());
        Set<PackageIdentity> directs = directIdentities(memberPath, effectiveConfig, workspace);
        boolean transitionalRoot = transitionalRoot(workspace, memberPath);

        List<LockPackage> packages = new ArrayList<>();
        List<LockMemberGraph> memberGraphs = new ArrayList<>();
        for (LockPackage lockPackage : aggregate.packages()) {
            if (!belongsToMember(lockPackage, memberPath, transitionalRoot)) {
                continue;
            }
            List<String> dependencies = graphIndex.dependenciesFor(memberPath, lockPackage);
            List<String> policies = graphIndex.policiesFor(memberPath, lockPackage);
            boolean workspaceDeclaredOptional =
                    workspaceOptional(workspace, memberPath, lockPackage);
            boolean declaredOptional = graphIndex.declaredOptionalFor(memberPath, lockPackage)
                    || workspaceDeclaredOptional;
            boolean optionalOnly = graphIndex.optionalOnlyFor(memberPath, lockPackage)
                    || workspaceDeclaredOptional;
            boolean direct = directs.contains(PackageIdentity.of(lockPackage));
            LockPackage projected = memberView(
                    lockPackage,
                    memberPath,
                    direct,
                    dependencies,
                    policies);
            packages.add(projected);
            memberGraphs.add(new LockMemberGraph(
                    memberPath,
                    projected.packageId(),
                    projected.version(),
                    LockArtifactVariant.of(projected),
                    projected.scope(),
                    projected.dependencies(),
                    projected.policies(),
                    declaredOptional,
                    optionalOnly));
        }

        List<LockConflict> conflicts = aggregate.conflicts().stream()
                .filter(conflict -> conflict.members().isEmpty()
                        || conflict.members().contains(memberPath))
                .filter(conflict -> describesProjectedPackage(conflict, packages))
                .map(conflict -> new LockConflict(
                        conflict.packageId(),
                        conflict.selectedVersion(),
                        conflict.requestedVersions(),
                        conflict.reason(),
                        conflict.toolGroup(),
                        conflict.variant(),
                        List.of(memberPath)))
                .toList();
        List<LockPolicyEffect> policyEffects =
                memberPolicyEffects(memberPath, effectiveConfig, packages, aggregate.policyEffects());
        return new ZoltLockfile(
                aggregate.version(),
                aggregate.aliasFingerprint(),
                aggregate.projectResolutionFingerprint(),
                aggregate.projectResolutionInputFingerprints(),
                List.copyOf(packages),
                conflicts,
                policyEffects,
                List.copyOf(memberGraphs));
    }

    private static Set<PackageIdentity> directIdentities(
            String memberPath,
            ProjectConfig config,
            Workspace workspace) {
        Set<PackageIdentity> identities = new LinkedHashSet<>();
        addExternal(identities, config, "api.dependencies", config.apiDependencies().keySet(), DependencyScope.COMPILE);
        addExternal(identities, config, "api.dependencies", config.managedApiDependencies(), DependencyScope.COMPILE);
        addExternal(identities, config, "dependencies", config.dependencies().keySet(), DependencyScope.COMPILE);
        addExternal(identities, config, "dependencies", config.managedDependencies(), DependencyScope.COMPILE);
        addExternal(
                identities,
                config,
                "runtime.dependencies",
                config.runtimeDependencies().keySet(),
                DependencyScope.RUNTIME);
        addExternal(
                identities,
                config,
                "runtime.dependencies",
                config.managedRuntimeDependencies(),
                DependencyScope.RUNTIME);
        addExternal(
                identities,
                config,
                "provided.dependencies",
                config.providedDependencies().keySet(),
                DependencyScope.PROVIDED);
        addExternal(
                identities,
                config,
                "provided.dependencies",
                config.managedProvidedDependencies(),
                DependencyScope.PROVIDED);
        addExternal(identities, config, "dev.dependencies", config.devDependencies().keySet(), DependencyScope.DEV);
        addExternal(identities, config, "dev.dependencies", config.managedDevDependencies(), DependencyScope.DEV);
        addExternal(identities, config, "test.dependencies", config.testDependencies().keySet(), DependencyScope.TEST);
        addExternal(identities, config, "test.dependencies", config.managedTestDependencies(), DependencyScope.TEST);
        addExternal(
                identities,
                config,
                "annotationProcessors",
                config.annotationProcessors().keySet(),
                DependencyScope.PROCESSOR);
        addExternal(
                identities,
                config,
                "annotationProcessors",
                config.managedAnnotationProcessors(),
                DependencyScope.PROCESSOR);
        addExternal(
                identities,
                config,
                "test.annotationProcessors",
                config.testAnnotationProcessors().keySet(),
                DependencyScope.TEST_PROCESSOR);
        addExternal(
                identities,
                config,
                "test.annotationProcessors",
                config.managedTestAnnotationProcessors(),
                DependencyScope.TEST_PROCESSOR);
        workspace.edges().stream()
                .filter(edge -> edge.from().equals(memberPath))
                .map(WorkspaceMemberPolicyLockProjection::workspaceIdentity)
                .forEach(identities::add);
        return Set.copyOf(identities);
    }

    private static void addExternal(
            Set<PackageIdentity> identities,
            ProjectConfig config,
            String section,
            Iterable<String> coordinates,
            DependencyScope scope) {
        for (String coordinate : coordinates) {
            DependencyMetadata metadata =
                    config.dependencyMetadata().get(DependencyMetadata.key(section, coordinate));
            LockArtifactVariant variant = metadata == null
                    ? LockArtifactVariant.defaultVariant()
                    : new LockArtifactVariant(
                            metadata.type() == null ? "jar" : metadata.type(),
                            Optional.ofNullable(metadata.classifier()));
            identities.add(new PackageIdentity(packageId(coordinate), variant, scope));
        }
    }

    private static PackageIdentity workspaceIdentity(WorkspaceProjectEdge edge) {
        return new PackageIdentity(
                packageId(edge.coordinate()),
                LockArtifactVariant.defaultVariant(),
                workspaceScope(edge.scope()));
    }

    private static boolean belongsToMember(
            LockPackage lockPackage,
            String memberPath,
            boolean transitionalRoot) {
        return lockPackage.members().contains(memberPath)
                || (transitionalRoot && lockPackage.members().isEmpty());
    }

    private static boolean transitionalRoot(Workspace workspace, String memberPath) {
        return ".".equals(memberPath)
                && workspace.members().size() == 1
                && workspace.members().getFirst().path().equals(".");
    }

    private static boolean workspaceOptional(
            Workspace workspace,
            String memberPath,
            LockPackage lockPackage) {
        if (lockPackage.workspace().isEmpty()) {
            return false;
        }
        return workspace.edges().stream()
                .filter(edge -> edge.from().equals(memberPath))
                .filter(edge -> edge.to().equals(lockPackage.workspace().orElseThrow()))
                .filter(edge -> packageId(edge.coordinate()).equals(lockPackage.packageId()))
                .filter(edge -> workspaceScope(edge.scope()) == lockPackage.scope())
                .anyMatch(WorkspaceProjectEdge::optional);
    }

    private static LockPackage memberView(
            LockPackage lockPackage,
            String memberPath,
            boolean direct,
            List<String> dependencies,
            List<String> policies) {
        return new LockPackage(
                lockPackage.packageId(),
                lockPackage.version(),
                lockPackage.source(),
                lockPackage.scope(),
                direct,
                lockPackage.jar(),
                lockPackage.pom(),
                lockPackage.jarSha256(),
                lockPackage.pomSha256(),
                lockPackage.artifact(),
                lockPackage.artifactType(),
                lockPackage.artifactSha256(),
                lockPackage.workspace(),
                lockPackage.workspaceOutput(),
                dependencies.stream().sorted().toList(),
                List.of(memberPath),
                lockPackage.exportedBy().contains(memberPath) ? List.of(memberPath) : List.of(),
                policies.stream().sorted().toList(),
                lockPackage.toolGroups());
    }

    private static boolean describesProjectedPackage(
            LockConflict conflict,
            List<LockPackage> packages) {
        LockArtifactVariant conflictVariant =
                conflict.variant().orElse(LockArtifactVariant.defaultVariant());
        return packages.stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(conflict.packageId())
                        && LockArtifactVariant.of(lockPackage).equals(conflictVariant));
    }

    private static List<LockPolicyEffect> memberPolicyEffects(
            String memberPath,
            ProjectConfig config,
            List<LockPackage> packages,
            List<LockPolicyEffect> effects) {
        Set<String> packagePolicies = packages.stream()
                .flatMap(lockPackage -> lockPackage.policies().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<PackageId> excluded = config.dependencyPolicy().exclusions().stream()
                .map(DependencyPolicyExclusion::coordinate)
                .map(WorkspaceMemberPolicyLockProjection::packageId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> memberSources = new LinkedHashSet<>();
        packages.forEach(lockPackage -> {
            memberSources.add(lockPackage.packageId() + ":" + lockPackage.version());
            memberSources.add(lockPackage.packageId().toString());
        });
        return effects.stream()
                .filter(effect -> packagePolicies.contains(effect.policy())
                        || workspaceMediationFor(effect, memberPath)
                        || globalExclusionFor(effect, excluded, memberSources))
                .distinct()
                .toList();
    }

    private static boolean workspaceMediationFor(
            LockPolicyEffect effect,
            String memberPath) {
        return "workspace-mediation".equals(effect.kind())
                && effect.source().map(source -> source.equals("workspace member " + memberPath)).orElse(false);
    }

    private static boolean globalExclusionFor(
            LockPolicyEffect effect,
            Set<PackageId> excluded,
            Set<String> memberSources) {
        if (!"global-exclusion".equals(effect.kind())
                || !excluded.contains(effect.packageId())) {
            return false;
        }
        return effect.source().isEmpty()
                || effect.source().map(memberSources::contains).orElse(false);
    }

    private static PackageId packageId(String coordinate) {
        String[] parts = coordinate.split(":", -1);
        return new PackageId(parts[0], parts[1]);
    }

    private static DependencyScope workspaceScope(String scope) {
        return switch (scope) {
            case "compile" -> DependencyScope.COMPILE;
            case "test" -> DependencyScope.TEST;
            case "processor" -> DependencyScope.PROCESSOR;
            case "test-processor" -> DependencyScope.TEST_PROCESSOR;
            default -> throw new IllegalArgumentException(
                    "Unsupported workspace dependency scope `" + scope + "`.");
        };
    }

    private record PackageIdentity(
            PackageId packageId,
            LockArtifactVariant variant,
            DependencyScope scope) {
        static PackageIdentity of(LockPackage lockPackage) {
            return new PackageIdentity(
                    lockPackage.packageId(),
                    LockArtifactVariant.of(lockPackage),
                    lockPackage.scope());
        }
    }
}
