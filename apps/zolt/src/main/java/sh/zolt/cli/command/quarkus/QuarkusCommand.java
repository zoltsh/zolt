package sh.zolt.cli.command.quarkus;

import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cli.ZoltCli;
import sh.zolt.cli.command.CommandAttributeKeys;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.command.CommandTimings;
import sh.zolt.cli.command.testplan.TestPlanCommand;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.perf.TimingRecorder;
import sh.zolt.project.ProjectConfig;
import sh.zolt.quarkus.QuarkusPlan;
import sh.zolt.quarkus.QuarkusPlanException;
import sh.zolt.quarkus.QuarkusPlanFormatter;
import sh.zolt.quarkus.QuarkusPlanService;
import sh.zolt.quarkus.production.QuarkusAugmentationRequestFactory;
import sh.zolt.quarkus.testplan.QuarkusTestPlan;
import sh.zolt.quarkus.testplan.QuarkusTestPlanFormatter;
import sh.zolt.quarkus.testplan.QuarkusTestPlanService;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import java.nio.file.Path;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "quarkus",
        description = "Inspect Quarkus build-time augmentation inputs.",
        subcommands = {
                QuarkusCommand.PlanCommand.class,
                QuarkusCommand.TestPlanCommand.class
        })
public final class QuarkusCommand implements Runnable {
    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(name = "plan", description = "Print the Quarkus augmentation input plan.")
    public static final class PlanCommand implements Runnable {
        private final ManifestProjectLoader projectLoader;
        private final QuarkusPlanService quarkusPlanService;
        private final QuarkusPlanFormatter quarkusPlanFormatter;
        private final QuarkusAugmentationRequestFactory augmentationRequestFactory;

        @Mixin
        private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

        @Option(names = "--cache-root", hidden = true)
        private Path cacheRoot = LocalArtifactCache.defaultRoot();

        @Mixin
        private ZoltCli.TimingOptions timingOptions = new ZoltCli.TimingOptions();

        @Spec
        private CommandSpec spec;

        public PlanCommand() {
            this(
                    new ManifestProjectLoader(),
                    new QuarkusPlanService(),
                    new QuarkusPlanFormatter(),
                    new QuarkusAugmentationRequestFactory());
        }

        PlanCommand(
                ManifestProjectLoader projectLoader,
                QuarkusPlanService quarkusPlanService,
                QuarkusPlanFormatter quarkusPlanFormatter,
                QuarkusAugmentationRequestFactory augmentationRequestFactory) {
            this.projectLoader = projectLoader;
            this.quarkusPlanService = quarkusPlanService;
            this.quarkusPlanFormatter = quarkusPlanFormatter;
            this.augmentationRequestFactory = augmentationRequestFactory;
        }

        @Override
        public void run() {
            TimingRecorder timings = CommandTimings.recorder(timingOptions);
            Path projectRoot = projectDirectory.path();
            try {
                ProjectConfig config = timings.measure(
                        "config read",
                        () -> projectLoader.load(projectRoot));
                QuarkusPlan plan = timings.measure(
                        "quarkus plan",
                        () -> quarkusPlanService.plan(projectRoot, config, cacheRoot),
                        QuarkusCommand::quarkusPlanAttributes);
                String output = timings.measure(
                        "quarkus plan format",
                        () -> quarkusPlanFormatter.format(plan));
                CommandOutput.printAndFlush(spec, output);
                timings.measure(
                        "quarkus augmentation request",
                        () -> augmentationRequestFactory.create(plan));
            } catch (LockfileReadException | QuarkusPlanException | ZoltConfigException exception) {
                throw CommandFailures.user(spec, exception);
            } finally {
                CommandTimings.print(spec, "quarkus plan", projectRoot, timingOptions, timings);
            }
        }
    }

    @Command(
            name = "test-plan",
            description = "Print the Quarkus test bootstrap plan.")
    public static final class TestPlanCommand implements Runnable {
        private final ManifestProjectLoader projectLoader;
        private final QuarkusTestPlanService quarkusTestPlanService;
        private final QuarkusTestPlanFormatter quarkusTestPlanFormatter;

        @Mixin
        private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

        @Spec
        private CommandSpec spec;

        public TestPlanCommand() {
            this(new ManifestProjectLoader(), new QuarkusTestPlanService(), new QuarkusTestPlanFormatter());
        }

        TestPlanCommand(
                ManifestProjectLoader projectLoader,
                QuarkusTestPlanService quarkusTestPlanService,
                QuarkusTestPlanFormatter quarkusTestPlanFormatter) {
            this.projectLoader = projectLoader;
            this.quarkusTestPlanService = quarkusTestPlanService;
            this.quarkusTestPlanFormatter = quarkusTestPlanFormatter;
        }

        @Override
        public void run() {
            try {
                Path projectRoot = projectDirectory.path();
                ProjectConfig config = projectLoader.load(projectRoot);
                QuarkusTestPlan plan = quarkusTestPlanService.plan(projectRoot, config);
                CommandOutput.printAndFlush(spec, quarkusTestPlanFormatter.format(plan));
            } catch (QuarkusPlanException | ZoltConfigException exception) {
                throw CommandFailures.user(spec, exception);
            }
        }
    }

    private static Map<String, String> quarkusPlanAttributes(QuarkusPlan plan) {
        return Map.of(
                CommandAttributeKeys.RUNTIME_CLASSPATH_ENTRIES, Integer.toString(plan.runtimeClasspath().size()),
                CommandAttributeKeys.DEPLOYMENT_CLASSPATH_ENTRIES, Integer.toString(plan.deploymentClasspath().size()),
                CommandAttributeKeys.EXTENSIONS, Integer.toString(plan.extensions().size()),
                CommandAttributeKeys.PACKAGE_MODE, plan.packageMode().configValue());
    }
}
