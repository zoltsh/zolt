package sh.zolt.workspace.service;

import sh.zolt.classpath.ClasspathSet;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WorkspaceCanonicalBuildPolicy {
    private static final List<String> PROCESSOR_EDGE_SCOPES = List.of("processor", "test-processor");

    private WorkspaceCanonicalBuildPolicy() {
    }

    static boolean hasGeneratedInputs(
            WorkspaceMember member,
            ClasspathSet classpaths) {
        return !classpaths.processor().entries().isEmpty()
                || !member.config().build().generatedMainSources().isEmpty();
    }

    static boolean hasFrameworkOutputs(WorkspaceMember member) {
        return member.config().frameworkSettings().springBoot().nativeEnabled();
    }

    static boolean generatesBuildMetadata(WorkspaceMember member) {
        var metadata = member.config().build().metadata();
        return metadata.buildInfo() || metadata.git();
    }

    /**
     * The members whose main processor classpath would come out non-empty, decided from the root
     * lock instead of by projecting one classpath per member.
     *
     * <p>A processor-lane package reaches a member only by naming it (or by naming nobody, which
     * means every member sees it) — the exported-API walk that widens the compile lane is filtered
     * to compile-classpath scopes and so cannot carry a processor package. A workspace
     * {@code processor} edge contributes the same way through the processor assembler.
     */
    static Set<String> membersWithProcessorInputs(
            Workspace workspace,
            ZoltLockfile lockfile) {
        Set<String> members = new LinkedHashSet<>();
        for (LockPackage lockPackage : lockfile.packages()) {
            if (lockPackage.workspace().isPresent()
                    || !lockPackage.scope().entersMainProcessorClasspath()) {
                continue;
            }
            if (lockPackage.members().isEmpty()) {
                return everyMember(workspace);
            }
            members.addAll(lockPackage.members());
        }
        for (WorkspaceProjectEdge edge : workspace.edges()) {
            if (edge.scope().equals("processor")) {
                members.add(edge.from());
            }
        }
        return members;
    }

    /**
     * The workspace members whose compiled output lands on {@code memberPath}'s processor path for
     * one edge scope, plus each of their own compile dependencies — a processor module may need
     * another member's classes at processing time.
     *
     * <p>These members are deliberately absent from the consumer's compile closure (see {@link
     * WorkspaceClasspathMemberGraph}), so their ABI reaches the consumer's dirtiness key only through
     * here. External processor jars need no equivalent: a processor-scoped lock package naming the
     * member sits in the member's own lane bucket and already moves its compile key.
     */
    static Set<String> processorMembers(
            Workspace workspace,
            String memberPath,
            Map<String, List<String>> compileDependenciesByMember) {
        Set<String> processors = new LinkedHashSet<>();
        for (String edgeScope : PROCESSOR_EDGE_SCOPES) {
            processors.addAll(processorMemberClosure(
                    workspace, memberPath, edgeScope, compileDependenciesByMember));
        }
        return processors;
    }

    static Set<String> processorMemberClosure(
            Workspace workspace,
            String memberPath,
            String edgeScope,
            Map<String, List<String>> compileDependenciesByMember) {
        Set<String> processorMembers = new LinkedHashSet<>();
        for (WorkspaceProjectEdge edge : workspace.edges()) {
            if (edge.from().equals(memberPath) && edge.scope().equals(edgeScope)) {
                includeMember(edge.to(), compileDependenciesByMember, processorMembers);
            }
        }
        return processorMembers;
    }

    private static void includeMember(
            String memberPath,
            Map<String, List<String>> dependenciesByMember,
            Set<String> closure) {
        if (!closure.add(memberPath)) {
            return;
        }
        for (String dependency : dependenciesByMember.getOrDefault(memberPath, List.of())) {
            includeMember(dependency, dependenciesByMember, closure);
        }
    }

    private static Set<String> everyMember(Workspace workspace) {
        Set<String> members = new LinkedHashSet<>();
        workspace.members().forEach(member -> members.add(member.path()));
        return members;
    }
}
