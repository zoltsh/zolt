package sh.zolt.resolve.lockfile.assembly;

import sh.zolt.cache.CachedArtifact;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.project.DependencyConstraint;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.DependencyPolicyEffect;
import sh.zolt.resolve.graph.PackageNode;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.fingerprint.ProjectResolutionFingerprint;
import sh.zolt.resolve.metadata.platform.ManagedVersion;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.selection.SelectedDependencyScope;
import sh.zolt.resolve.selection.SelectedDependencyScopes;
import sh.zolt.resolve.version.VersionSelectionResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class LockfileAssembler {
    private final CoordinateParser coordinateParser;

    public LockfileAssembler(CoordinateParser coordinateParser) {
        this.coordinateParser = coordinateParser;
    }

    public ZoltLockfile assemble(
            LockfileAssemblyContext context,
            ResolutionGraph graph,
            VersionSelectionResult selection,
            List<DependencyRequest> directRequests) {
        return assemble(context, graph, selection, directRequests, List.of());
    }

    public ZoltLockfile assemble(
            LockfileAssemblyContext context,
            ResolutionGraph graph,
            VersionSelectionResult selection,
            List<DependencyRequest> directRequests,
            List<ExecToolResolution> execResolutions) {
        long started = System.nanoTime();
        try {
            Map<PackageNode, List<SelectedDependencyScope>> selectedScopes = SelectedDependencyScopes.from(
                    graph,
                    selection,
                    directRequests);
            List<LockPackagePlan> packagePlans = new ArrayList<>(selection.selectedNodes().stream()
                    .flatMap(node -> selectedScopes
                            .getOrDefault(node, List.of(new SelectedDependencyScope(DependencyScope.COMPILE, false)))
                            .stream()
                            .map(scope -> LockPackagePlan.of(node, scope, graph, selection, List.of())))
                    .toList());
            packagePlans.addAll(ExecToolLockPlanner.plans(execResolutions));
            Map<ArtifactDescriptor, CachedArtifact> artifacts = context.getArtifacts(
                    packagePlans.stream().map(LockPackagePlan::artifactDescriptor).distinct().toList());
            Map<PackageId, List<DependencyScope>> managedDirectScopes = managedDirectScopes(context.config());
            Map<PackageId, ManagedVersion> managedVersionDetails = context.projectManagedVersionDetails();
            List<LockPackage> packages = packagePlans.stream()
                    .map(plan -> lockPackage(
                            context,
                            plan,
                            artifacts.get(plan.artifactDescriptor()),
                            managedDirectScopes,
                            managedVersionDetails,
                            context.config().dependencyMetadata()))
                    .toList();
            List<LockConflict> conflicts = mergedConflicts(selection, execResolutions);
            return new ZoltLockfile(
                    ZoltLockfile.CURRENT_VERSION,
                    aliasFingerprint(context.config()),
                    Optional.of(ProjectResolutionFingerprint.fingerprint(context.config())),
                    ProjectResolutionFingerprint.inputFingerprints(context.config()),
                    packages,
                    conflicts,
                    LockfilePolicyPlanner.lockPolicyEffects(mergedPolicyEffects(graph, execResolutions)));
        } finally {
            context.addLockfileAssemblyNanos(elapsedSince(started));
        }
    }

    /**
     * Every recorded mediation across the main graph and every isolated exec-tool closure (Hole 1). Main
     * conflicts keep an empty tool group; each tool's conflicts are tagged with the tool name so the audit
     * trail names WHICH closure mediated (the same GA may mediate in the main graph and in several tools,
     * each a distinct entry). Tools are visited in sorted name order for deterministic assembly.
     */
    private static List<LockConflict> mergedConflicts(
            VersionSelectionResult selection, List<ExecToolResolution> execResolutions) {
        List<LockConflict> conflicts = new ArrayList<>(lockConflicts(selection, Optional.empty()));
        execResolutions.stream()
                .sorted(Comparator.comparing(ExecToolResolution::toolName))
                .forEach(tool -> conflicts.addAll(lockConflicts(tool.selection(), Optional.of(tool.toolName()))));
        return conflicts;
    }

    private static List<LockConflict> lockConflicts(VersionSelectionResult selection, Optional<String> toolGroup) {
        return selection.conflicts().stream()
                .map(conflict -> new LockConflict(
                        conflict.packageId(),
                        conflict.selectedVersion(),
                        conflict.requests().stream().map(DependencyRequest::requestedVersion).toList(),
                        conflict.selectionReason(),
                        toolGroup,
                        conflict.variant().isDefault()
                                ? Optional.empty()
                                : Optional.of(conflict.variant())))
                .toList();
    }

    /**
     * Policy effects from the main graph plus every exec-tool closure, unioned. {@link
     * LockfilePolicyPlanner#lockPolicyEffects} dedups and sorts, so an effect shared by the main graph and
     * a tool collapses to one entry and the aggregate audit ordering stays deterministic.
     */
    private static List<DependencyPolicyEffect> mergedPolicyEffects(
            ResolutionGraph graph, List<ExecToolResolution> execResolutions) {
        List<DependencyPolicyEffect> effects = new ArrayList<>(graph.policyEffects());
        for (ExecToolResolution tool : execResolutions) {
            effects.addAll(tool.graph().policyEffects());
        }
        return effects;
    }

    private static Optional<String> aliasFingerprint(ProjectConfig config) {
        List<String> inputs = new ArrayList<>();
        config.versionAliases().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> "alias\t" + entry.getKey() + "\t" + entry.getValue())
                .forEach(inputs::add);
        config.dependencyMetadata().values().stream()
                .filter(metadata -> metadata.versionRef() != null)
                .sorted(Comparator
                        .comparing(DependencyMetadata::section)
                        .thenComparing(DependencyMetadata::coordinate))
                .map(metadata -> "versionRef\t"
                        + metadata.section()
                        + "\t"
                        + metadata.coordinate()
                        + "\t"
                        + metadata.versionRef()
                        + "\t"
                        + nullToEmpty(metadata.version()))
                .forEach(inputs::add);
        config.dependencyPolicy().constraints().values().stream()
                .filter(constraint -> constraint.versionRef().isPresent())
                .sorted(Comparator.comparing(DependencyConstraint::coordinate))
                .map(constraint -> "constraintVersionRef\t"
                        + constraint.coordinate()
                        + "\t"
                        + constraint.versionRef().orElseThrow()
                        + "\t"
                        + constraint.version())
                .forEach(inputs::add);
        openApiSteps(config).stream()
                .map(GeneratedSourceStep::openApi)
                .filter(settings -> settings.toolVersionRef().isPresent())
                .sorted(Comparator
                        .comparing((OpenApiGenerationSettings settings) -> settings.toolCoordinate().orElse(""))
                        .thenComparing(settings -> settings.toolVersionRef().orElseThrow()))
                .map(settings -> "openApiToolVersionRef\t"
                        + settings.toolCoordinate().orElse("")
                        + "\t"
                        + settings.toolVersionRef().orElseThrow()
                        + "\t"
                        + settings.toolVersion().orElse(""))
                .forEach(inputs::add);
        protobufSteps(config).stream()
                .map(GeneratedSourceStep::protobuf)
                .flatMap(settings -> java.util.stream.Stream.of(
                        protobufVersionRefInput("protobufProtocVersionRef", settings.protocCoordinate(), settings.protocVersionRef(), settings.protocVersion()),
                        protobufVersionRefInput("protobufGrpcPluginVersionRef", settings.grpcPluginCoordinate(), settings.grpcPluginVersionRef(), settings.grpcPluginVersion())))
                .flatMap(Optional::stream)
                .sorted()
                .forEach(inputs::add);
        if (inputs.isEmpty()) {
            return Optional.empty();
        }
        String input = String.join("\n", inputs) + "\n";
        return Optional.of("sha256:" + sha256(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<GeneratedSourceStep> openApiSteps(ProjectConfig config) {
        List<GeneratedSourceStep> steps = new ArrayList<>();
        config.build().generatedMainSources().stream()
                .filter(step -> step.kind() == GeneratedSourceKind.OPENAPI)
                .forEach(steps::add);
        config.build().generatedTestSources().stream()
                .filter(step -> step.kind() == GeneratedSourceKind.OPENAPI)
                .forEach(steps::add);
        return List.copyOf(steps);
    }

    private static List<GeneratedSourceStep> protobufSteps(ProjectConfig config) {
        List<GeneratedSourceStep> steps = new ArrayList<>();
        config.build().generatedMainSources().stream()
                .filter(step -> step.kind() == GeneratedSourceKind.PROTOBUF)
                .forEach(steps::add);
        config.build().generatedTestSources().stream()
                .filter(step -> step.kind() == GeneratedSourceKind.PROTOBUF)
                .forEach(steps::add);
        return List.copyOf(steps);
    }

    private static Optional<String> protobufVersionRefInput(
            String label,
            Optional<String> coordinate,
            Optional<String> versionRef,
            Optional<String> version) {
        if (versionRef.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(label
                + "\t"
                + coordinate.orElse("")
                + "\t"
                + versionRef.orElseThrow()
                + "\t"
                + version.orElse(""));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private LockPackage lockPackage(
            LockfileAssemblyContext context,
            LockPackagePlan plan,
            CachedArtifact artifact,
            Map<PackageId, List<DependencyScope>> managedDirectScopes,
            Map<PackageId, ManagedVersion> managedVersionDetails,
            Map<String, DependencyMetadata> dependencyMetadata) {
        PackageNode node = plan.node();
        SelectedDependencyScope selectedScope = plan.selectedScope();
        ArtifactDescriptor descriptor = plan.artifactDescriptor();
        CachedArtifact pom = context.getPom(descriptor.coordinate());
        boolean jarArtifact = "jar".equals(descriptor.extension());
        return new LockPackage(
                node.packageId(),
                node.selectedVersion(),
                context.sourceFor(artifact),
                selectedScope.scope(),
                selectedScope.direct(),
                jarArtifact ? Optional.of(artifact.repositoryPath()) : Optional.empty(),
                Optional.of(pom.repositoryPath()),
                jarArtifact ? Optional.of(context.digest(artifact)) : Optional.empty(),
                Optional.of(context.digest(pom)),
                jarArtifact ? Optional.empty() : Optional.of(artifact.repositoryPath()),
                jarArtifact ? Optional.empty() : Optional.of(descriptor.extension()),
                jarArtifact ? Optional.empty() : Optional.of(context.digest(artifact)),
                Optional.empty(),
                Optional.empty(),
                LockfileEdges.dependenciesFor(
                        node,
                        selectedScope.scope(),
                        plan.graph(),
                        plan.selection()),
                List.of(),
                List.of(),
                LockfilePolicyPlanner.policiesFor(
                        node,
                        selectedScope,
                        context.config().dependencyPolicy().constraints(),
                        managedDirectScopes,
                        managedVersionDetails,
                        dependencyMetadata,
                        plan.graph().policyEffects()),
                plan.toolGroups());
    }

    private Map<PackageId, List<DependencyScope>> managedDirectScopes(ProjectConfig config) {
        Map<PackageId, List<DependencyScope>> scopes = new LinkedHashMap<>();
        addManagedDirectScopes(scopes, config.managedApiDependencies(), DependencyScope.COMPILE);
        addManagedDirectScopes(scopes, config.managedDependencies(), DependencyScope.COMPILE);
        addManagedDirectScopes(scopes, config.managedRuntimeDependencies(), DependencyScope.RUNTIME);
        addManagedDirectScopes(scopes, config.managedProvidedDependencies(), DependencyScope.PROVIDED);
        addManagedDirectScopes(scopes, config.managedDevDependencies(), DependencyScope.DEV);
        addManagedDirectScopes(scopes, config.managedTestDependencies(), DependencyScope.TEST);
        addManagedDirectScopes(scopes, config.managedAnnotationProcessors(), DependencyScope.PROCESSOR);
        addManagedDirectScopes(scopes, config.managedTestAnnotationProcessors(), DependencyScope.TEST_PROCESSOR);
        return scopes.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .distinct()
                                .sorted(Comparator.comparing(DependencyScope::lockfileName))
                                .toList(),
                        (first, second) -> first,
                        LinkedHashMap::new));
    }

    private void addManagedDirectScopes(
            Map<PackageId, List<DependencyScope>> scopes,
            Iterable<String> dependencies,
            DependencyScope scope) {
        for (String dependency : dependencies) {
            Coordinate coordinate = coordinateParser.parse(dependency);
            scopes.computeIfAbsent(PackageId.from(coordinate), ignored -> new ArrayList<>()).add(scope);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new ResolveException("Could not compute artifact checksum because SHA-256 is unavailable.", exception);
        }
    }

    private static long elapsedSince(long started) {
        return Math.max(0L, System.nanoTime() - started);
    }
}
