package sh.zolt.workspace.resolve;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.resolve.ResolutionVariant;
import sh.zolt.resolve.ResolveException;
import sh.zolt.project.PackageMode;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The authoritative plain-jar artifacts supplied by explicit workspace dependency visibility.
 *
 * <p>Merely listing a member does not replace a released artifact of the same GA. A consumer shadows
 * an external plain jar only when the provider is in that consumer's explicit, scope-correct workspace
 * dependency closure. Classified and typed attachments remain separate variant lanes. Only a
 * {@link PackageMode#THIN} member supplies a reusable library JAR; executable/application, uber, WAR,
 * and BOM modes are publication artifacts, not ordinary workspace dependency providers.
 */
final class WorkspaceProvidedArtifactMediator {
    private final Map<PackageId, ProvidedArtifact> provided;
    private final Map<VisibilityKey, Set<String>> visibleMembers;

    WorkspaceProvidedArtifactMediator(Workspace workspace) {
        requireLibraryProviders(workspace);
        Map<PackageId, ProvidedArtifact> values = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            if (!suppliesPlainJar(member)) {
                continue;
            }
            PackageId packageId = new PackageId(
                    member.config().project().group(),
                    member.config().project().name());
            values.put(packageId, new ProvidedArtifact(
                    packageId,
                    member.path(),
                    member.config().project().version()));
        }
        provided = Map.copyOf(values);
        visibleMembers = visibility(workspace);
    }

    private static void requireLibraryProviders(Workspace workspace) {
        Map<String, WorkspaceMember> membersByPath = new LinkedHashMap<>();
        workspace.members().forEach(member -> membersByPath.put(member.path(), member));
        workspace.edges().forEach(edge -> {
            WorkspaceMember target = membersByPath.get(edge.to());
            if (target == null || suppliesPlainJar(target)) {
                return;
            }
            PackageMode mode = target.config().packageSettings().mode();
            throw ResolveException.actionable(
                    "Workspace dependency `"
                            + edge.coordinate()
                            + "` in member `"
                            + edge.from()
                            + "` targets member `"
                            + target.path()
                            + "`, whose package mode is `"
                            + mode.configValue()
                            + "`. Executable, application, WAR, and BOM packaging is not a reusable library artifact.",
                    "Split shared code into a separate workspace member with package mode `thin`, then depend on that member.");
        });
    }

    boolean shadows(String consumer, LockPackage lockPackage) {
        return lockPackage.workspace().isEmpty()
                && LockArtifactVariant.of(lockPackage).isDefault()
                && provided(consumer, lockPackage.packageId(), lockPackage.scope()).isPresent();
    }

    Optional<ProvidedArtifact> provided(PackageId packageId) {
        return Optional.ofNullable(provided.get(packageId));
    }

    Optional<ProvidedArtifact> provided(
            String consumer,
            PackageId packageId,
            DependencyScope scope) {
        ProvidedArtifact artifact = provided.get(packageId);
        if (artifact == null
                || !visibleMembers
                        .getOrDefault(new VisibilityKey(consumer, scope), Set.of())
                        .contains(artifact.member())) {
            return Optional.empty();
        }
        return Optional.of(artifact);
    }

    Map<ResolutionVariant, String> selectedVersions() {
        Map<ResolutionVariant, String> versions = new LinkedHashMap<>();
        provided.values().stream()
                .sorted(Comparator.comparing(value -> value.packageId().toString()))
                .forEach(value -> versions.put(
                        new ResolutionVariant(
                                value.packageId(),
                                LockArtifactVariant.defaultVariant()),
                        value.version()));
        return Map.copyOf(versions);
    }

    List<LockPackage> shadowedCandidates(
            List<WorkspaceMemberResolveOutput> outputs) {
        List<LockPackage> candidates = new ArrayList<>();
        for (WorkspaceMemberResolveOutput output : outputs) {
            output.lockfile().packages().stream()
                    .filter(lockPackage -> shadows(output.member(), lockPackage))
                    .map(lockPackage -> withMember(lockPackage, output.member()))
                    .forEach(candidates::add);
        }
        return List.copyOf(candidates);
    }

    private static boolean suppliesPlainJar(WorkspaceMember member) {
        return member.config().packageSettings().mode() == PackageMode.THIN;
    }

    private static Map<VisibilityKey, Set<String>> visibility(Workspace workspace) {
        Map<String, List<sh.zolt.workspace.service.WorkspaceProjectEdge>> edges = new LinkedHashMap<>();
        workspace.members().forEach(member -> edges.put(member.path(), new ArrayList<>()));
        workspace.edges().forEach(edge ->
                edges.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge));
        Map<VisibilityKey, Set<String>> visibility = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            String consumer = member.path();
            Set<String> compile = new LinkedHashSet<>();
            direct(edges, consumer, "compile").forEach(edge -> {
                if (compile.add(edge.to())) {
                    includeExported(edges, edge.to(), compile);
                }
            });
            putVisibility(visibility, consumer, DependencyScope.COMPILE, compile);
            putVisibility(visibility, consumer, DependencyScope.PROVIDED, compile);

            Set<String> runtime = new LinkedHashSet<>();
            direct(edges, consumer, "compile").forEach(edge -> {
                if (runtime.add(edge.to())) {
                    includeCompileClosure(edges, edge.to(), runtime);
                }
            });
            putVisibility(visibility, consumer, DependencyScope.RUNTIME, runtime);
            putVisibility(visibility, consumer, DependencyScope.DEV, runtime);

            Set<String> test = new LinkedHashSet<>(runtime);
            direct(edges, consumer, "test").forEach(edge -> {
                if (test.add(edge.to())) {
                    includeCompileClosure(edges, edge.to(), test);
                }
            });
            putVisibility(visibility, consumer, DependencyScope.TEST, test);

            putVisibility(
                    visibility,
                    consumer,
                    DependencyScope.PROCESSOR,
                    processorVisibility(edges, consumer, "processor"));
            putVisibility(
                    visibility,
                    consumer,
                    DependencyScope.TEST_PROCESSOR,
                    processorVisibility(edges, consumer, "test-processor"));
        }
        return Map.copyOf(visibility);
    }

    private static Set<String> processorVisibility(
            Map<String, List<sh.zolt.workspace.service.WorkspaceProjectEdge>> edges,
            String consumer,
            String scope) {
        Set<String> visible = new LinkedHashSet<>();
        direct(edges, consumer, scope).forEach(edge -> {
            if (visible.add(edge.to())) {
                includeCompileClosure(edges, edge.to(), visible);
            }
        });
        return visible;
    }

    private static List<sh.zolt.workspace.service.WorkspaceProjectEdge> direct(
            Map<String, List<sh.zolt.workspace.service.WorkspaceProjectEdge>> edges,
            String member,
            String scope) {
        return edges.getOrDefault(member, List.of()).stream()
                .filter(edge -> edge.scope().equals(scope))
                .toList();
    }

    private static void includeExported(
            Map<String, List<sh.zolt.workspace.service.WorkspaceProjectEdge>> edges,
            String member,
            Set<String> visible) {
        direct(edges, member, "compile").stream()
                .filter(sh.zolt.workspace.service.WorkspaceProjectEdge::exported)
                .filter(edge -> !edge.optional())
                .forEach(edge -> {
                    if (visible.add(edge.to())) {
                        includeExported(edges, edge.to(), visible);
                    }
                });
    }

    private static void includeCompileClosure(
            Map<String, List<sh.zolt.workspace.service.WorkspaceProjectEdge>> edges,
            String member,
            Set<String> visible) {
        direct(edges, member, "compile").stream()
                .filter(edge -> !edge.optional())
                .forEach(edge -> {
                    if (visible.add(edge.to())) {
                        includeCompileClosure(edges, edge.to(), visible);
                    }
                });
    }

    private static void putVisibility(
            Map<VisibilityKey, Set<String>> visibility,
            String member,
            DependencyScope scope,
            Set<String> values) {
        visibility.put(new VisibilityKey(member, scope), Set.copyOf(values));
    }

    List<LockPackage> policyCandidates(
            List<WorkspaceMemberResolveOutput> outputs) {
        List<LockPackage> shadowed = shadowedCandidates(outputs);
        List<LockPackage> candidates = new ArrayList<>(shadowed);
        Map<ProvidedScope, Set<String>> members = new LinkedHashMap<>();
        for (LockPackage candidate : shadowed) {
            ProvidedArtifact target = provided.get(candidate.packageId());
            ProvidedScope key = new ProvidedScope(target, candidate.scope());
            members.computeIfAbsent(key, ignored -> new LinkedHashSet<>())
                    .addAll(candidate.members());
        }
        for (Map.Entry<ProvidedScope, Set<String>> entry : members.entrySet()) {
            candidates.add(workspaceCandidate(
                    entry.getKey().provided(),
                    entry.getKey().scope(),
                    entry.getValue()));
        }
        return List.copyOf(candidates);
    }

    List<LockConflict> conflicts(
            List<WorkspaceMemberResolveOutput> outputs) {
        return new WorkspaceExternalPackageSelector()
                .versionConflicts(policyCandidates(outputs));
    }

    private static LockPackage workspaceCandidate(
            ProvidedArtifact provided,
            DependencyScope scope,
            Set<String> members) {
        return new LockPackage(
                provided.packageId(),
                provided.version(),
                "workspace",
                scope,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(provided.member()),
                Optional.empty(),
                List.of(),
                members.stream().sorted().toList(),
                List.of(),
                List.of(),
                List.of());
    }

    private static LockPackage withMember(
            LockPackage lockPackage,
            String member) {
        return new LockPackage(
                lockPackage.packageId(),
                lockPackage.version(),
                lockPackage.source(),
                lockPackage.scope(),
                lockPackage.direct(),
                lockPackage.jar(),
                lockPackage.pom(),
                lockPackage.jarSha256(),
                lockPackage.pomSha256(),
                lockPackage.artifact(),
                lockPackage.artifactType(),
                lockPackage.artifactSha256(),
                lockPackage.workspace(),
                lockPackage.workspaceOutput(),
                lockPackage.dependencies(),
                List.of(member),
                lockPackage.exportedBy(),
                lockPackage.policies(),
                lockPackage.toolGroups());
    }

    record ProvidedArtifact(
            PackageId packageId,
            String member,
            String version) {
    }

    private record ProvidedScope(
            ProvidedArtifact provided,
            DependencyScope scope) {
    }

    private record VisibilityKey(String member, DependencyScope scope) {
    }
}
