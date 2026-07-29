package sh.zolt.workspace.service;

import sh.zolt.doctor.JdkChecker;
import java.util.Objects;

@FunctionalInterface
public interface WorkspaceJdkCheckerResolver {
    JdkChecker forMember(Workspace workspace, WorkspaceMember member);

    default Object cacheKey(
            Workspace workspace,
            WorkspaceMember member,
            JdkChecker checker) {
        return checker;
    }

    static WorkspaceJdkCheckerResolver fixed(JdkChecker jdkChecker) {
        Objects.requireNonNull(jdkChecker, "jdkChecker");
        return (workspace, member) -> jdkChecker;
    }
}
