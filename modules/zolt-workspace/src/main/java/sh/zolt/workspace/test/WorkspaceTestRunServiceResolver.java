package sh.zolt.workspace.test;

import sh.zolt.build.testruntime.TestRunService;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.util.Objects;

@FunctionalInterface
public interface WorkspaceTestRunServiceResolver {
    TestRunService forMember(Workspace workspace, WorkspaceMember member);

    default WorkspaceTestToolchainMetrics toolchainMetrics() {
        return WorkspaceTestToolchainMetrics.empty();
    }

    static WorkspaceTestRunServiceResolver fixed(TestRunService testRunService) {
        Objects.requireNonNull(testRunService, "testRunService");
        return (workspace, member) -> testRunService;
    }
}
