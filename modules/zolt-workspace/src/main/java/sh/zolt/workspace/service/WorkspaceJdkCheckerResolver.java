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

    default String compileIdentity(
            Workspace workspace,
            WorkspaceMember member,
            JdkChecker checker,
            Object cacheKey) {
        if (member == null) {
            return "unspecified-workspace-toolchain";
        }
        return member.config().project().java()
                + "|"
                + member.config().compilerSettings();
    }

    default int lockfileParseCount() {
        return 0;
    }

    static WorkspaceJdkCheckerResolver fixed(JdkChecker jdkChecker) {
        Objects.requireNonNull(jdkChecker, "jdkChecker");
        return (workspace, member) -> jdkChecker;
    }
}
