package sh.zolt.cli.command;

import sh.zolt.cli.command.toolchain.CommandJavaToolchainJdkChecker;
import sh.zolt.cli.command.toolchain.TestRuntimeJdkChecker;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toolchain.JavaToolchainExecutionService;
import sh.zolt.toolchain.TestRuntimeToolchain;
import sh.zolt.toolchain.TestRuntimeToolchainResolver;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import sh.zolt.workspace.service.WorkspaceJdkCheckerResolver;
import sh.zolt.workspace.service.WorkspaceTestRunServiceResolver;
import java.nio.file.Path;
import java.util.Optional;
import picocli.CommandLine.Option;

public final class CommandToolchainOptions {
    @Option(names = "--toolchain-target", hidden = true)
    private String toolchainTarget;

    @Option(names = "--toolchain-install-root", hidden = true)
    private Path toolchainInstallRoot;

    public CommandJavaToolchainJdkChecker jdkChecker(
            Path projectRoot,
            ProjectConfig config,
            String commandName) {
        return jdkChecker(projectRoot, projectRoot, config, commandName);
    }

    public CommandJavaToolchainJdkChecker jdkChecker(
            Path projectRoot,
            Path lockRoot,
            ProjectConfig config,
            String commandName) {
        return CommandJavaToolchainJdkChecker.forCommand(
                projectRoot,
                lockRoot,
                config,
                toolchainTarget,
                toolchainInstallRoot,
                commandName);
    }

    /**
     * The {@link JdkChecker} that RUNS tests: the resolved {@code [toolchain.java.test]} runtime
     * toolchain when a project declares one, otherwise {@code buildChecker} (unchanged behavior).
     * Resolution is eager, so an unready test toolchain fails with actionable guidance before compile.
     */
    public JdkChecker testRuntimeRunChecker(Path projectRoot, ProjectConfig config, JdkChecker buildChecker) {
        return testRuntimeRunChecker(projectRoot, projectRoot, config, buildChecker, workspaceToolchainServices());
    }

    private JdkChecker testRuntimeRunChecker(
            Path projectRoot,
            Path lockRoot,
            ProjectConfig config,
            JdkChecker buildChecker,
            WorkspaceToolchainServices services) {
        Optional<TestRuntimeToolchain> resolved = new TestRuntimeToolchainResolver()
                .resolve(projectRoot, lockRoot, config, services.platform(), services.store());
        return resolved.<JdkChecker>map(TestRuntimeJdkChecker::of).orElse(buildChecker);
    }

    public WorkspaceJdkCheckerResolver workspaceJdkCheckers(String commandName) {
        return workspaceContext(commandName).mainCheckers();
    }

    public WorkspaceCommandToolchains workspaceTestToolchains(
            CommandServiceBundles.TestRunServiceFactory factory,
            String commandName) {
        WorkspaceCommandToolchainContext context =
                workspaceContext(commandName);
        return new WorkspaceCommandToolchains(
                context.mainCheckers(),
                context.testRunServices(factory));
    }

    public WorkspaceCommandToolchains workspaceIntegrationTestToolchains(
            CommandServiceBundles.TestRunServiceFactory factory) {
        WorkspaceCommandToolchainContext context =
                workspaceContext("integration-test");
        return new WorkspaceCommandToolchains(
                context.mainCheckers(),
                context.testRunServices(factory));
    }

    public WorkspaceCommandToolchains workspaceCoverageToolchains(
            CommandServiceBundles.TestRunServiceFactory factory) {
        return workspaceTestToolchains(factory, "coverage");
    }

    private WorkspaceCommandToolchainContext workspaceContext(
            String commandName) {
        return new WorkspaceCommandToolchainContext(
                toolchainTarget,
                toolchainInstallRoot,
                commandName);
    }

    private WorkspaceToolchainServices workspaceToolchainServices() {
        return new WorkspaceToolchainServices(
                new JavaToolchainExecutionService(),
                HostPlatform.parse(toolchainTarget),
                new ToolchainStore(toolchainInstallRoot));
    }

    private record WorkspaceToolchainServices(
            JavaToolchainExecutionService toolchains,
            HostPlatform platform,
            ToolchainStore store) {
    }

    public record WorkspaceCommandToolchains(
            WorkspaceJdkCheckerResolver mainCheckers,
            WorkspaceTestRunServiceResolver testRunServices) {
    }
}
