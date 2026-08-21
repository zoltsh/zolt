package sh.zolt.cli.command;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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
import sh.zolt.cli.command.CommandServiceClusters.CommandBuildFrameworkServices;
import sh.zolt.cli.command.CommandServiceClusters.CommandConfigEditServices;
import sh.zolt.cli.command.CommandServiceClusters.CommandPackageFrameworkServices;
import sh.zolt.cli.command.CommandServiceClusters.CommandRunFrameworkServices;
import sh.zolt.cli.command.CommandServiceClusters.CommandTestFrameworkServices;
import sh.zolt.resolve.ResolveService;
import org.junit.jupiter.api.Test;

final class CommandFrameworkServicesTest {
    @Test
    void coverageCommandServicesOwnsDefaultCoverageWiring() {
        CommandCoverageServices services = CommandFrameworkServices.coverageCommandServices();

        assertNotNull(services.projectLoader());
        assertNotNull(services.resolveService());
        assertNotNull(services.coverageService());
        assertNotNull(services.workspaceCoverageService());
    }

    @Test
    void coverageCommandsShareTheSuppliedCommandResolver() {
        ResolveService resolveService = new ResolveService();

        CommandCoverageServices services =
                CommandFrameworkServices.coverageCommandServices(
                        resolveService);

        assertSame(resolveService, services.resolveService());
    }

    @Test
    void nativeCommandServicesOwnsDefaultNativeWiring() {
        CommandNativeServices services = CommandFrameworkServices.nativeCommandServices();

        assertNotNull(services.projectLoader());
        assertNotNull(services.nativeBuildService());
        assertNotNull(services.workspaceNativeBuildService());
    }

    @Test
    void versionAliasCommandServicesOwnsDefaultVersionAliasWiring() {
        CommandVersionAliasServices services = CommandFrameworkServices.versionAliasCommandServices();

        assertNotNull(services.tomlParser());
        assertNotNull(services.tomlWriter());
        assertNotNull(services.resolveService());
    }

    @Test
    void configEditServicesOwnsDefaultConfigEditWiring() {
        CommandConfigEditServices services = CommandFrameworkServices.configEditServices();

        assertNotNull(services.tomlParser());
        assertNotNull(services.tomlWriter());
        assertNotNull(services.resolveService());
    }

    @Test
    void dependencyEditCommandServicesOwnsDefaultDependencyEditWiring() {
        CommandDependencyEditServices services = CommandFrameworkServices.dependencyEditCommandServices();

        assertNotNull(services.coordinateParser());
        assertNotNull(services.tomlParser());
        assertNotNull(services.tomlWriter());
        assertNotNull(services.resolveService());
    }

    @Test
    void resolveCommandServicesOwnsDefaultResolveWiring() {
        CommandResolveServices services = CommandFrameworkServices.resolveCommandServices();

        assertNotNull(services.resolveService());
        assertNotNull(services.workspaceResolveService());
    }

    @Test
    void buildCommandServicesOwnsDefaultBuildWiring() {
        CommandBuildServices services = CommandFrameworkServices.buildCommandServices();

        assertNotNull(services.buildService());
        assertNotNull(services.workspaceBuildService());
        assertNotNull(services.frameworkBuildAugmenter());
    }

    @Test
    void buildFrameworkServicesOwnsDefaultBuildFrameworkWiring() {
        CommandBuildFrameworkServices services = CommandFrameworkServices.buildFrameworkServices();

        assertNotNull(services.frameworkBuildAugmenter());
        assertNotNull(services.resolveService());
    }

    @Test
    void packageCommandServicesOwnsDefaultPackageWiring() {
        CommandPackageServices services = CommandFrameworkServices.packageCommandServices();

        assertNotNull(services.packagePlanService());
        assertNotNull(services.packageService());
        assertNotNull(services.buildService());
        assertNotNull(services.workspacePackageService());
    }

    @Test
    void packageFrameworkServicesOwnsDefaultPackageFrameworkWiring() {
        CommandPackageFrameworkServices services = CommandFrameworkServices.packageFrameworkServices();

        assertNotNull(services.packageAugmenter());
        assertNotNull(services.packagePlanService());
    }

    @Test
    void qualityCommandServicesOwnsDefaultQualityWiring() {
        CommandQualityServices services = CommandFrameworkServices.qualityCommandServices();

        assertNotNull(services.qualityCheckService());
    }

    @Test
    void publishCommandServicesOwnsDefaultPublishWiring() {
        CommandPublishServices services = CommandFrameworkServices.publishCommandServices();

        assertNotNull(services.publishDryRunService());
        assertNotNull(services.workspacePublishService());
    }

    @Test
    void runPackageCommandServicesOwnsDefaultRunPackageWiring() {
        CommandRunPackageServices services = CommandFrameworkServices.runPackageCommandServices();

        assertNotNull(services.runPackageService());
        assertNotNull(services.workspaceRunPackageService());
    }

    @Test
    void runCommandServicesOwnsDefaultRunWiring() {
        CommandRunServices services = CommandFrameworkServices.runCommandServices();

        assertNotNull(services.runService());
        assertNotNull(services.workspaceRunService());
    }

    @Test
    void runFrameworkServicesOwnsDefaultRunFrameworkWiring() {
        CommandRunFrameworkServices services = CommandFrameworkServices.runFrameworkServices();

        assertNotNull(services.frameworkRunAugmenter());
        assertNotNull(services.resolveService());
    }

    @Test
    void testCommandServicesOwnsDefaultTestWiring() {
        CommandTestServices services = CommandFrameworkServices.testCommandServices();

        assertNotNull(services.testRunService());
        assertNotNull(services.workspaceTestService());
    }

    @Test
    void testFrameworkServicesOwnsDefaultTestFrameworkWiring() {
        CommandTestFrameworkServices services = CommandFrameworkServices.testFrameworkServices();

        assertNotNull(services.frameworkTestRunner());
        assertNotNull(services.resolveService());
    }
}
