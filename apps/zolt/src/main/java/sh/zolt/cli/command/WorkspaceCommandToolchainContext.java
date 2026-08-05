package sh.zolt.cli.command;

import sh.zolt.doctor.JdkChecker;
import sh.zolt.toolchain.JavaToolchainExecutionService;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceJdkCheckerResolver;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.test.WorkspaceTestRunServiceResolver;
import sh.zolt.workspace.test.WorkspaceTestToolchainMetrics;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

final class WorkspaceCommandToolchainContext {
    private final JavaToolchainExecutionService toolchains;
    private final HostPlatform platform;
    private final ToolchainStore store;
    private final String commandName;
    private final Map<Path, WorkspaceCommandToolchainState> workspaces =
            new HashMap<>();

    WorkspaceCommandToolchainContext(
            String toolchainTarget,
            Path toolchainInstallRoot,
            String commandName) {
        this(
                new JavaToolchainExecutionService(),
                HostPlatform.parse(toolchainTarget),
                new ToolchainStore(toolchainInstallRoot),
                commandName);
    }

    WorkspaceCommandToolchainContext(
            JavaToolchainExecutionService toolchains,
            HostPlatform platform,
            ToolchainStore store,
            String commandName) {
        this.toolchains = toolchains;
        this.platform = platform;
        this.store = store;
        this.commandName = commandName;
    }

    WorkspaceJdkCheckerResolver mainCheckers() {
        return new WorkspaceJdkCheckerResolver() {
            @Override
            public JdkChecker forMember(
                    Workspace workspace,
                    WorkspaceMember member) {
                return state(workspace).mainChecker(workspace, member);
            }

            @Override
            public Object cacheKey(
                    Workspace workspace,
                    WorkspaceMember member,
                    JdkChecker checker) {
                return state(workspace).mainKey(workspace, member);
            }

            @Override
            public String compileIdentity(
                    Workspace workspace,
                    WorkspaceMember member,
                    JdkChecker checker,
                    Object cacheKey) {
                return state(workspace).compileIdentity(cacheKey);
            }

            @Override
            public int lockfileParseCount() {
                return WorkspaceCommandToolchainContext.this
                        .lockfileParseCount();
            }
        };
    }

    WorkspaceTestRunServiceResolver testRunServices(
            CommandServiceBundles.TestRunServiceFactory factory) {
        return new WorkspaceTestRunServiceResolver() {
            @Override
            public sh.zolt.build.testruntime.TestRunService forMember(
                    Workspace workspace,
                    WorkspaceMember member) {
                JdkChecker compileChecker =
                        state(workspace).mainChecker(workspace, member);
                JdkChecker runtimeChecker = requiredVersion ->
                        state(workspace)
                                .testRuntimeChecker(workspace, member)
                                .detect(requiredVersion);
                return factory.create(compileChecker, runtimeChecker);
            }

            @Override
            public WorkspaceTestToolchainMetrics toolchainMetrics() {
                return metrics();
            }
        };
    }

    private synchronized WorkspaceCommandToolchainState state(
            Workspace workspace) {
        Path root = workspace.root().toAbsolutePath().normalize();
        return workspaces.computeIfAbsent(
                root,
                ignored -> new WorkspaceCommandToolchainState(
                        workspace,
                        toolchains,
                        platform,
                        store,
                        commandName));
    }

    private synchronized int lockfileParseCount() {
        return workspaces.values().stream()
                .mapToInt(
                        WorkspaceCommandToolchainState::lockfileParseCount)
                .sum();
    }

    private synchronized WorkspaceTestToolchainMetrics metrics() {
        int testIdentityCalculations = workspaces.values().stream()
                .mapToInt(WorkspaceCommandToolchainState::testIdentityCalculations)
                .sum();
        int testIdentityHits = workspaces.values().stream()
                .mapToInt(WorkspaceCommandToolchainState::testIdentityHits)
                .sum();
        return new WorkspaceTestToolchainMetrics(
                lockfileParseCount(),
                0,
                0,
                testIdentityCalculations,
                testIdentityHits);
    }
}
