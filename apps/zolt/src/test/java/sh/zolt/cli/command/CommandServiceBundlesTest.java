package sh.zolt.cli.command;

import static sh.zolt.cli.command.CommandServiceNullGuardAssertions.assertRejectsNullCollaborators;

import sh.zolt.cli.command.CommandServiceBundles.CommandBuildServices;
import sh.zolt.cli.command.CommandServiceBundles.CommandCoverageServices;
import sh.zolt.cli.command.CommandServiceBundles.CommandDependencyEditServices;
import sh.zolt.cli.command.CommandServiceBundles.CommandNativeServices;
import sh.zolt.cli.command.CommandServiceBundles.CommandPackageServices;
import sh.zolt.cli.command.CommandServiceBundles.CommandPublishServices;
import sh.zolt.cli.command.CommandServiceBundles.CommandQualityServices;
import sh.zolt.cli.command.CommandServiceBundles.CommandResolveServices;
import sh.zolt.cli.command.CommandServiceBundles.CommandRunPackageServices;
import sh.zolt.cli.command.CommandServiceBundles.CommandRunServices;
import sh.zolt.cli.command.CommandServiceBundles.CommandTestServices;
import sh.zolt.cli.command.CommandServiceBundles.CommandVersionAliasServices;
import org.junit.jupiter.api.Test;

final class CommandServiceBundlesTest {
    @Test
    void coverageCommandServicesRequiresEveryCollaborator() {
        CommandCoverageServices services = CommandFrameworkServices.coverageCommandServices();

        assertRejectsNullCollaborators(
                () -> new CommandCoverageServices(
                        null,
                        services.coverageService(),
                        services.workspaceCoverageService(),
                        services.coverageServiceFactory(),
                        services.testRunServiceFactory()),
                () -> new CommandCoverageServices(
                        services.tomlParser(),
                        null,
                        services.workspaceCoverageService(),
                        services.coverageServiceFactory(),
                        services.testRunServiceFactory()),
                () -> new CommandCoverageServices(
                        services.tomlParser(),
                        services.coverageService(),
                        null,
                        services.coverageServiceFactory(),
                        services.testRunServiceFactory()),
                () -> new CommandCoverageServices(
                        services.tomlParser(),
                        services.coverageService(),
                        services.workspaceCoverageService(),
                        null,
                        services.testRunServiceFactory()),
                () -> new CommandCoverageServices(
                        services.tomlParser(),
                        services.coverageService(),
                        services.workspaceCoverageService(),
                        services.coverageServiceFactory(),
                        null));
    }

    @Test
    void nativeCommandServicesRequiresEveryCollaborator() {
        CommandNativeServices services = CommandFrameworkServices.nativeCommandServices();

        assertRejectsNullCollaborators(
                () -> new CommandNativeServices(
                        null,
                        services.nativeBuildService(),
                        services.workspaceNativeBuildService()),
                () -> new CommandNativeServices(
                        services.tomlParser(),
                        null,
                        services.workspaceNativeBuildService()),
                () -> new CommandNativeServices(
                        services.tomlParser(),
                        services.nativeBuildService(),
                        null));
    }

    @Test
    void versionAliasCommandServicesRequiresEveryCollaborator() {
        CommandVersionAliasServices services = CommandFrameworkServices.versionAliasCommandServices();

        assertRejectsNullCollaborators(
                () -> new CommandVersionAliasServices(
                        null));
    }

    @Test
    void dependencyEditCommandServicesRequiresEveryCollaborator() {
        CommandDependencyEditServices services = CommandFrameworkServices.dependencyEditCommandServices();

        assertRejectsNullCollaborators(
                () -> new CommandDependencyEditServices(
                        null,
                        services.configEditServices()),
                () -> new CommandDependencyEditServices(
                        services.coordinateParser(),
                        null));
    }

    @Test
    void resolveCommandServicesRequiresEveryCollaborator() {
        CommandResolveServices services = CommandFrameworkServices.resolveCommandServices();

        assertRejectsNullCollaborators(
                () -> new CommandResolveServices(
                        null,
                        services.workspaceResolveService()),
                () -> new CommandResolveServices(
                        services.resolveService(),
                        null));
    }

    @Test
    void buildCommandServicesRequiresEveryCollaborator() {
        CommandBuildServices services = CommandFrameworkServices.buildCommandServices();

        assertRejectsNullCollaborators(
                () -> new CommandBuildServices(
                        null,
                        services.workspaceBuildService(),
                        services.frameworkBuildAugmenter()),
                () -> new CommandBuildServices(
                        services.buildService(),
                        null,
                        services.frameworkBuildAugmenter()),
                () -> new CommandBuildServices(
                        services.buildService(),
                        services.workspaceBuildService(),
                        null));
    }

    @Test
    void packageCommandServicesRequiresEveryCollaborator() {
        CommandPackageServices services = CommandFrameworkServices.packageCommandServices();

        assertRejectsNullCollaborators(
                () -> new CommandPackageServices(
                        null,
                        services.packageService(),
                        services.buildService(),
                        services.workspacePackageService()),
                () -> new CommandPackageServices(
                        services.packagePlanService(),
                        null,
                        services.buildService(),
                        services.workspacePackageService()),
                () -> new CommandPackageServices(
                        services.packagePlanService(),
                        services.packageService(),
                        null,
                        services.workspacePackageService()),
                () -> new CommandPackageServices(
                        services.packagePlanService(),
                        services.packageService(),
                        services.buildService(),
                        null));
    }

    @Test
    void qualityCommandServicesRequiresEveryCollaborator() {
        assertRejectsNullCollaborators(
                () -> new CommandQualityServices(null));
    }

    @Test
    void publishCommandServicesRequiresEveryCollaborator() {
        CommandPublishServices services = CommandFrameworkServices.publishCommandServices();

        assertRejectsNullCollaborators(
                () -> new CommandPublishServices(
                        null,
                        services.workspacePublishService()),
                () -> new CommandPublishServices(
                        services.publishDryRunService(),
                        null));
    }

    @Test
    void runPackageCommandServicesRequiresEveryCollaborator() {
        CommandRunPackageServices services = CommandFrameworkServices.runPackageCommandServices();

        assertRejectsNullCollaborators(
                () -> new CommandRunPackageServices(
                        null,
                        services.workspaceRunPackageService()),
                () -> new CommandRunPackageServices(
                        services.runPackageService(),
                        null));
    }

    @Test
    void runCommandServicesRequiresEveryCollaborator() {
        CommandRunServices services = CommandFrameworkServices.runCommandServices();

        assertRejectsNullCollaborators(
                () -> new CommandRunServices(
                        null,
                        services.workspaceRunService()),
                () -> new CommandRunServices(
                        services.runService(),
                        null));
    }

    @Test
    void testCommandServicesRequiresEveryCollaborator() {
        CommandTestServices services = CommandFrameworkServices.testCommandServices();

        assertRejectsNullCollaborators(
                () -> new CommandTestServices(
                        null,
                        services.workspaceTestService(),
                        services.testRunServiceFactory()),
                () -> new CommandTestServices(
                        services.testRunService(),
                        null,
                        services.testRunServiceFactory()),
                () -> new CommandTestServices(
                        services.testRunService(),
                        services.workspaceTestService(),
                        null));
    }

}
