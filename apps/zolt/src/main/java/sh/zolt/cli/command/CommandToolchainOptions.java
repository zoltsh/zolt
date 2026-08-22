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
import sh.zolt.workspace.test.WorkspaceTestRunServiceResolver;
import java.nio.file.Path;
import java.util.Optional;
import picocli.CommandLine.Option;

public final class CommandToolchainOptions {
    @Option(names = "--toolchain-target", hidden = true)
    private String toolchainTarget;

    @Option(names = "--toolchain-install-root", hidden = true)
    private Path toolchainInstallRoot;

    /**
     * The build toolchain for a command boundary: the request is read from the project's own manifest
     * and the locked toolchain from the directory that owns the lock. For a member those are two
     * different directories (design §4.5), which is why this never derives one from the other.
     */
    public CommandJavaToolchainJdkChecker jdkChecker(
            ProjectCommandContext context,
            String commandName) {
        return jdkChecker(context, context.config(), commandName);
    }

    /** As {@link #jdkChecker(ProjectCommandContext, String)}, for a command-adjusted config. */
    public CommandJavaToolchainJdkChecker jdkChecker(
            ProjectCommandContext context,
            ProjectConfig config,
            String commandName) {
        return jdkChecker(context.projectRoot(), context.lockRoot(), config, commandName);
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
    public JdkChecker testRuntimeRunChecker(ProjectCommandContext context, JdkChecker buildChecker) {
        return testRuntimeRunChecker(context, context.config(), buildChecker);
    }

    /** As {@link #testRuntimeRunChecker(ProjectCommandContext, JdkChecker)}, for an adjusted config. */
    public JdkChecker testRuntimeRunChecker(
            ProjectCommandContext context,
            ProjectConfig config,
            JdkChecker buildChecker) {
        return testRuntimeRunChecker(
                context.projectRoot(),
                context.lockRoot(),
                config,
                buildChecker,
                workspaceToolchainServices());
    }

    /**
     * The resolved {@code [toolchain.java.test]} runtime toolchain for reporting paths — {@code zolt
     * plan --target test} and {@code zolt toolchain status} — which describe it rather than run on it.
     */
    public Optional<TestRuntimeToolchain> testRuntimeToolchain(ProjectCommandContext context) {
        WorkspaceToolchainServices services = workspaceToolchainServices();
        return new TestRuntimeToolchainResolver().resolve(
                context.projectRoot(),
                context.lockRoot(),
                context.config(),
                services.platform(),
                services.store());
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
