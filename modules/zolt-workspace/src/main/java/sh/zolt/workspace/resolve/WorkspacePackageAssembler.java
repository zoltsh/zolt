package sh.zolt.workspace.resolve;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyEdge;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import sh.zolt.resolve.ResolveException;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceProjectEdge;

final class WorkspacePackageAssembler {
    List<LockPackage> assemble(
            Workspace workspace,
            List<WorkspaceMemberResolveOutput> memberOutputs,
            WorkspaceProvidedArtifactMediator provided) {
        Map<String, WorkspaceMember> membersByPath = membersByPath(workspace);
        Map<String, WorkspaceMemberResolveOutput> outputsByMember =
                new LinkedHashMap<>();
        memberOutputs.forEach(
                output -> outputsByMember.put(output.member(), output));
        Map<WorkspaceCoordinateScope, WorkspacePackageFacts> facts =
                collectFacts(
                        workspace,
                        memberOutputs,
                        provided,
                        membersByPath);
        List<LockPackage> packages = new ArrayList<>();
        for (Map.Entry<WorkspaceCoordinateScope, WorkspacePackageFacts> entry :
                facts.entrySet()) {
            WorkspaceCoordinateScope key = entry.getKey();
            WorkspacePackageFacts value = entry.getValue();
            WorkspaceMember target = value.target();
            packages.add(new LockPackage(
                    key.packageId(),
                    target.config().project().version(),
                    "workspace",
                    key.scope(),
                    value.direct(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(target.path()),
                    Optional.of(workspaceOutput(workspace.root(), target)),
                    providerDependencies(
                            target.path(),
                            outputsByMember,
                            provided),
                    value.members().stream().sorted().toList(),
                    value.exportedBy().stream().sorted().toList(),
                    List.of(),
                    List.of()));
        }
        return List.copyOf(packages);
    }

    private static Map<WorkspaceCoordinateScope, WorkspacePackageFacts>
            collectFacts(
                    Workspace workspace,
                    List<WorkspaceMemberResolveOutput> memberOutputs,
                    WorkspaceProvidedArtifactMediator provided,
                    Map<String, WorkspaceMember> membersByPath) {
        Map<WorkspaceCoordinateScope, WorkspacePackageFacts> facts =
                new LinkedHashMap<>();
        for (WorkspaceProjectEdge edge : workspace.edges()) {
            if (provided.provided(packageId(edge.coordinate())).isEmpty()) {
                continue;
            }
            WorkspaceMember target = membersByPath.get(edge.to());
            WorkspaceCoordinateScope key = new WorkspaceCoordinateScope(
                    packageId(edge.coordinate()),
                    LockArtifactVariant.defaultVariant(),
                    scope(edge.scope()));
            WorkspacePackageFacts value = facts.computeIfAbsent(
                    key, ignored -> new WorkspacePackageFacts(target));
            value.members().add(edge.from());
            if (edge.exported() && !edge.optional()) {
                value.exportedBy().add(edge.from());
            }
            value.markDirect();
        }
        for (WorkspaceMemberResolveOutput output : memberOutputs) {
            for (LockPackage lockPackage : output.lockfile().packages()) {
                if (!provided.shadows(output.member(), lockPackage)) {
                    continue;
                }
                WorkspaceProvidedArtifactMediator.ProvidedArtifact target =
                        provided.provided(
                                        lockPackage.packageId())
                                .orElseThrow();
                WorkspaceCoordinateScope key = new WorkspaceCoordinateScope(
                        target.packageId(),
                        LockArtifactVariant.defaultVariant(),
                        lockPackage.scope());
                facts.computeIfAbsent(
                                key,
                                ignored -> new WorkspacePackageFacts(
                                        membersByPath.get(target.member())))
                        .members()
                        .add(output.member());
            }
        }
        return facts;
    }

    private static List<String> providerDependencies(
            String targetMember,
            Map<String, WorkspaceMemberResolveOutput> outputsByMember,
            WorkspaceProvidedArtifactMediator provided) {
        WorkspaceMemberResolveOutput output =
                outputsByMember.get(targetMember);
        if (output == null) {
            return List.of();
        }
        return output.lockfile().packages().stream()
                .filter(LockPackage::direct)
                .filter(lockPackage -> !provided.shadows(targetMember, lockPackage))
                .filter(lockPackage -> !output.optionalPackages().contains(
                        new WorkspaceOptionalPackage(
                                lockPackage.packageId(),
                                LockArtifactVariant.of(lockPackage),
                                lockPackage.scope())))
                .filter(lockPackage ->
                        lockPackage.scope() == DependencyScope.COMPILE
                                || lockPackage.scope()
                                        == DependencyScope.RUNTIME)
                .map(LockDependencyEdge::of)
                .map(LockDependencyEdge::encode)
                .sorted()
                .toList();
    }

    private static String workspaceOutput(
            Path workspaceRoot,
            WorkspaceMember member) {
        String configuredOutput = member.config().build().output();
        try {
            Path memberRoot = ProjectPaths.existingRoot(
                    ProjectPaths.root(workspaceRoot),
                    "[workspace].members",
                    member.path());
            ProjectPaths.output(
                    memberRoot, "[build].output", configuredOutput);
            return configuredOutput;
        } catch (ProjectPathException exception) {
            throw new ResolveException(
                    "Workspace member `"
                            + member.path()
                            + "` has an invalid [build].output. "
                            + exception.getMessage(),
                    exception);
        }
    }

    private static Map<String, WorkspaceMember> membersByPath(
            Workspace workspace) {
        Map<String, WorkspaceMember> members = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            members.put(member.path(), member);
        }
        return members;
    }

    private static PackageId packageId(String coordinate) {
        String[] parts = coordinate.split(":", -1);
        return new PackageId(parts[0], parts[1]);
    }

    private static DependencyScope scope(String value) {
        return switch (value) {
            case "compile" -> DependencyScope.COMPILE;
            case "test" -> DependencyScope.TEST;
            case "processor" -> DependencyScope.PROCESSOR;
            case "test-processor" -> DependencyScope.TEST_PROCESSOR;
            default -> throw new ResolveException(
                    "Unsupported workspace dependency scope `"
                            + value
                            + "`.");
        };
    }

    private record WorkspaceCoordinateScope(
            PackageId packageId,
            LockArtifactVariant variant,
            DependencyScope scope) {
    }

    private static final class WorkspacePackageFacts {
        private final WorkspaceMember target;
        private final Set<String> members = new LinkedHashSet<>();
        private final Set<String> exportedBy = new LinkedHashSet<>();
        private boolean direct;

        private WorkspacePackageFacts(WorkspaceMember target) {
            this.target = target;
        }

        WorkspaceMember target() {
            return target;
        }

        Set<String> members() {
            return members;
        }

        Set<String> exportedBy() {
            return exportedBy;
        }

        boolean direct() {
            return direct;
        }

        void markDirect() {
            direct = true;
        }
    }
}
