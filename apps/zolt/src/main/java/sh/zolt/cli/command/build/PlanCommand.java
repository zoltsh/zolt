package sh.zolt.cli.command.build;

import sh.zolt.build.testruntime.TestReportSettings;
import sh.zolt.test.runtime.TestRunException;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.plan.BuildPlan;
import sh.zolt.plan.BuildPlanFormatter;
import sh.zolt.plan.BuildPlanRequest;
import sh.zolt.plan.BuildPlanService;
import sh.zolt.plan.PlanTarget;
import sh.zolt.plan.TestRuntimePlan;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toolchain.TestRuntimeToolchain;
import sh.zolt.toolchain.TestRuntimeToolchainResolver;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.discovery.ManifestProject;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "plan", description = "Show the typed Zolt command plan without executing it.")
public final class PlanCommand implements Callable<Integer> {
    private final ManifestProjectLoader projectLoader;
    private final BuildPlanService buildPlanService;
    private final BuildPlanFormatter buildPlanFormatter;

    enum Format {
        TEXT,
        JSON
    }

    @Option(names = "--target", description = "Plan target: build, test, package, native, or ci.")
    private PlanTarget target = PlanTarget.PACKAGE;

    @Option(names = "--reports-dir", description = "Include a project-relative test report output in test/ci plans.")
    private Path reportsDir;

    @Option(names = "--native-image", description = "Path to the native-image executable for native plans.")
    private Path nativeImageExecutable;

    @Option(names = "--format", description = "Output format: text or json.")
    private Format format = Format.TEXT;

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Spec
    private CommandSpec spec;

    public PlanCommand() {
        this(new ManifestProjectLoader(), new BuildPlanService(), new BuildPlanFormatter());
    }

    PlanCommand(
            ManifestProjectLoader projectLoader,
            BuildPlanService buildPlanService,
            BuildPlanFormatter buildPlanFormatter) {
        this.projectLoader = projectLoader;
        this.buildPlanService = buildPlanService;
        this.buildPlanFormatter = buildPlanFormatter;
    }

    @Override
    public Integer call() {
        try {
            Path projectRoot = projectDirectory.path();
            ManifestProject project = projectLoader.project(projectRoot);
            ProjectConfig config = project.config();
            TestReportSettings reportSettings = TestReportSettings.reportsDirectory(reportsDir);
            BuildPlan plan = buildPlanService.plan(new BuildPlanRequest(
                    projectRoot,
                    BuildPlanRequest.lockfileFor(projectRoot, project.workspaceRoot()),
                    config,
                    target,
                    reportSettings.projectRelativeReportsDirectory(projectRoot),
                    Optional.ofNullable(nativeImageExecutable),
                    testRuntimePlan(projectRoot, config)));
            if (format == Format.JSON) {
                CommandOutput.printAndFlush(spec, buildPlanFormatter.json(plan));
            } else {
                CommandOutput.printAndFlush(spec, buildPlanFormatter.text(plan));
            }
            return plan.blocked() ? 1 : 0;
        } catch (TestRunException | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }

    private Optional<TestRuntimePlan> testRuntimePlan(Path projectRoot, ProjectConfig config) {
        if (!target.includesTests()) {
            return Optional.empty();
        }
        return new TestRuntimeToolchainResolver()
                .resolve(projectRoot, projectRoot, config, HostPlatform.current(), ToolchainStore.defaults())
                .map(PlanCommand::toTestRuntimePlan);
    }

    private static TestRuntimePlan toTestRuntimePlan(TestRuntimeToolchain toolchain) {
        return new TestRuntimePlan(
                toolchain.request().version(),
                toolchain.ready(),
                toolchain.problem(),
                toolchain.remediation());
    }
}
