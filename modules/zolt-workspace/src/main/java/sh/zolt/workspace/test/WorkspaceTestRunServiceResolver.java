package sh.zolt.workspace.test;

import sh.zolt.build.cache.BuildCacheService;
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

    default WorkspaceTestRunServiceResolver withBuildCache(BuildCacheService buildCacheService) {
        Objects.requireNonNull(buildCacheService, "buildCacheService");
        WorkspaceTestRunServiceResolver delegate = this;
        return new WorkspaceTestRunServiceResolver() {
            @Override
            public TestRunService forMember(Workspace workspace, WorkspaceMember member) {
                return delegate.forMember(workspace, member).withBuildCache(buildCacheService);
            }

            @Override
            public WorkspaceTestToolchainMetrics toolchainMetrics() {
                return delegate.toolchainMetrics();
            }
        };
    }

    static WorkspaceTestRunServiceResolver fixed(TestRunService testRunService) {
        Objects.requireNonNull(testRunService, "testRunService");
        return (workspace, member) -> testRunService;
    }
}
