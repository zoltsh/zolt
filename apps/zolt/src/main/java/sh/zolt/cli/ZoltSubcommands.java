package sh.zolt.cli;

import sh.zolt.cli.command.build.BuildCommand;
import sh.zolt.cli.command.build.CleanCommand;
import sh.zolt.cli.command.build.PlanCommand;
import sh.zolt.cli.command.build.RunCommand;
import sh.zolt.cli.command.cache.CacheCommand;
import sh.zolt.cli.command.config.ConfigCommand;
import sh.zolt.cli.command.config.InitCommand;
import sh.zolt.cli.command.dependency.AddCommand;
import sh.zolt.cli.command.dependency.BomCommand;
import sh.zolt.cli.command.dependency.ConflictsCommand;
import sh.zolt.cli.command.dependency.OutdatedCommand;
import sh.zolt.cli.command.dependency.PlatformsCommand;
import sh.zolt.cli.command.dependency.RemoveCommand;
import sh.zolt.cli.command.dependency.UpdateCommand;
import sh.zolt.cli.command.dependency.VersionCommand;
import sh.zolt.cli.command.dependency.VersionsCommand;
import sh.zolt.cli.command.ide.IdeCommand;
import sh.zolt.cli.command.insight.ExplainCommand;
import sh.zolt.cli.command.insight.TreeCommand;
import sh.zolt.cli.command.insight.WhyCommand;
import sh.zolt.cli.command.nativeimage.NativeCommand;
import sh.zolt.cli.command.nativeimage.NativeSmokeCommand;
import sh.zolt.cli.command.packaging.PackageCommand;
import sh.zolt.cli.command.packaging.RunPackageCommand;
import sh.zolt.cli.command.packaging.SelfParityCommand;
import sh.zolt.cli.command.publish.PublishCommand;
import sh.zolt.cli.command.publish.ReleaseArchiveCommand;
import sh.zolt.cli.command.publish.ReleaseIndexCommand;
import sh.zolt.cli.command.publish.ReleaseVerifyCommand;
import sh.zolt.cli.command.quality.CheckCommand;
import sh.zolt.cli.command.quality.CoverageCommand;
import sh.zolt.cli.command.quality.DoctorCommand;
import sh.zolt.cli.command.quality.PolicyCommand;
import sh.zolt.cli.command.quarkus.QuarkusCommand;
import sh.zolt.cli.command.resolve.ClasspathCommand;
import sh.zolt.cli.command.resolve.ResolveCommand;
import sh.zolt.cli.command.self.SelfCommand;
import sh.zolt.cli.command.selfhost.SelfCheckCommand;
import sh.zolt.cli.command.supplychain.LicensesCommand;
import sh.zolt.cli.command.supplychain.SbomCommand;
import sh.zolt.cli.command.task.AliasesCommand;
import sh.zolt.cli.command.task.TaskCommand;
import sh.zolt.cli.command.task.TasksCommand;
import sh.zolt.cli.command.testcmd.IntegrationTestCommand;
import sh.zolt.cli.command.testcmd.TestCommand;
import sh.zolt.cli.command.toolchain.ExecCommand;
import sh.zolt.cli.command.toolchain.ShimsCommand;
import sh.zolt.cli.command.toolchain.ToolchainCommand;
import sh.zolt.cli.command.workspace.WorkspaceCommand;
import java.util.List;
import picocli.CommandLine;

/**
 * The shipped command inventory, in help order.
 *
 * <p>The tree is registered here rather than in a {@code @Command(subcommands = ...)} annotation on
 * {@link ZoltCli} so the root command stays about its own options and execution wiring. The
 * inventory grows with every new command; keeping it here is what stops the root command's
 * dependency surface from mirroring every command package.
 *
 * <p>Order is load-bearing: {@code zolt --list} and the root usage render subcommands in registration
 * order, so an entry belongs next to the commands it is used with, not in alphabetical order.
 */
final class ZoltSubcommands {
    private static final List<Class<?>> COMMANDS = List.of(
            ZoltHelpCommand.class,
            InitCommand.class,
            VersionCommand.class,
            VersionsCommand.class,
            WorkspaceCommand.class,
            SelfCommand.class,
            ConfigCommand.class,
            CacheCommand.class,
            CheckCommand.class,
            AddCommand.class,
            RemoveCommand.class,
            PlatformsCommand.class,
            BomCommand.class,
            ResolveCommand.class,
            TreeCommand.class,
            WhyCommand.class,
            PolicyCommand.class,
            ConflictsCommand.class,
            OutdatedCommand.class,
            UpdateCommand.class,
            ExplainCommand.class,
            PlanCommand.class,
            ClasspathCommand.class,
            IdeCommand.class,
            ToolchainCommand.class,
            ExecCommand.class,
            ShimsCommand.class,
            QuarkusCommand.class,
            AliasesCommand.class,
            TasksCommand.class,
            TaskCommand.class,
            BuildCommand.class,
            RunCommand.class,
            TestCommand.class,
            IntegrationTestCommand.class,
            CoverageCommand.class,
            PackageCommand.class,
            PublishCommand.class,
            SbomCommand.class,
            LicensesCommand.class,
            RunPackageCommand.class,
            NativeCommand.class,
            NativeSmokeCommand.class,
            ReleaseArchiveCommand.class,
            ReleaseIndexCommand.class,
            ReleaseVerifyCommand.class,
            SelfCheckCommand.class,
            SelfParityCommand.class,
            CleanCommand.class,
            DoctorCommand.class);

    private ZoltSubcommands() {
    }

    /**
     * Registers every shipped command on {@code commandLine}. Callers must add the tree before
     * applying parser settings and universal help, which picocli propagates only to the subcommands
     * that already exist.
     */
    static void addTo(CommandLine commandLine) {
        COMMANDS.forEach(commandLine::addSubcommand);
    }
}
