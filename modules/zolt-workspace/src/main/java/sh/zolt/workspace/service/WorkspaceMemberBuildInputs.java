package sh.zolt.workspace.service;

import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.project.PackageMode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The per-member facts a workspace build settles before stage 0 can decide anything — the lanes each
 * member needs and the toolchain it would compile with — plus the on-demand source stage 1 pulls
 * classpaths from.
 */
final class WorkspaceMemberBuildInputs {
    private final WorkspaceExecutionContext context;
    private final WorkspaceClasspathService classpathService;
    private final Map<String, WorkspaceBuildRequirements> requirements;
    private final Map<String, String> toolchainIdentities;

    WorkspaceMemberBuildInputs(
            WorkspaceExecutionContext context,
            WorkspaceClasspathService classpathService,
            WorkspaceJdkCheckerResolver jdkCheckers,
            WorkspaceSelection selection,
            Map<String, WorkspaceMember> membersByPath,
            Map<String, WorkspaceBuildRequirements> requestedRequirements) {
        this.context = context;
        this.classpathService = classpathService;
        WorkspaceBuildRequirementResolver requirementResolver =
                new WorkspaceBuildRequirementResolver();
        Map<String, WorkspaceBuildRequirements> resolved = new LinkedHashMap<>();
        Map<String, String> identities = new LinkedHashMap<>();
        for (String member : selection.includedMembers()) {
            WorkspaceMember workspaceMember = membersByPath.get(member);
            resolved.put(
                    member,
                    requirementResolver.forMember(
                            requestedRequirements.getOrDefault(
                                    member,
                                    WorkspaceBuildRequirements.mainBuild()),
                            workspaceMember.config()));
            identities.put(
                    member,
                    workspaceMember.config().packageSettings().mode() == PackageMode.BOM
                            ? "not-applicable:bom"
                            : context.toolchainIndex().compileIdentity(
                                    jdkCheckers,
                                    context.workspace(),
                                    workspaceMember));
        }
        this.requirements = Map.copyOf(resolved);
        this.toolchainIdentities = Map.copyOf(identities);
    }

    Map<String, WorkspaceBuildRequirements> requirements() {
        return requirements;
    }

    Map<String, String> toolchainIdentities() {
        return toolchainIdentities;
    }

    WorkspaceMemberClasspaths classpaths() {
        return new WorkspaceMemberClasspaths() {
            @Override
            public ClasspathSet forMember(String memberPath) {
                return classpathService.classpathsFor(context, memberPath, requirementsFor(memberPath));
            }

            @Override
            public List<ResolvedClasspathPackage> packagesForMember(String memberPath) {
                return requirementsFor(memberPath).packageInputs()
                        ? classpathService.classpathPackagesFor(context, memberPath)
                        : List.of();
            }
        };
    }

    /**
     * Test classes are stale when stage 0 saw their own inputs move, and also whenever the member's
     * main output was rewritten in this build — including members a dependency ABI change dragged in
     * after stage 0 had already decided.
     */
    static Set<String> testCompileRequired(
            WorkspaceSelection selection,
            WorkspaceDirtyPlan dirtyPlan,
            Set<String> executedMembers) {
        Set<String> required = new LinkedHashSet<>(executedMembers);
        for (String member : selection.includedMembers()) {
            if (dirtyPlan.member(member).testCompileRequired()) {
                required.add(member);
            }
        }
        return required;
    }

    private WorkspaceBuildRequirements requirementsFor(String memberPath) {
        return requirements.getOrDefault(memberPath, WorkspaceBuildRequirements.mainBuild());
    }
}
