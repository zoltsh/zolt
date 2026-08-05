package sh.zolt.workspace.service;

import sh.zolt.classpath.ClasspathSet;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.LinkedHashSet;
import java.util.Set;

final class WorkspaceCanonicalBuildPolicy {
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

    private static Set<String> everyMember(Workspace workspace) {
        Set<String> members = new LinkedHashSet<>();
        workspace.members().forEach(member -> members.add(member.path()));
        return members;
    }
}
