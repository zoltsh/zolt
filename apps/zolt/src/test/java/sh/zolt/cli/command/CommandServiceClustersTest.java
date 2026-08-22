package sh.zolt.cli.command;

import static sh.zolt.cli.command.CommandServiceNullGuardAssertions.assertRejectsNullCollaborators;

import sh.zolt.cli.command.CommandServiceClusters.CommandBuildFrameworkServices;
import sh.zolt.cli.command.CommandServiceClusters.CommandConfigEditServices;
import sh.zolt.cli.command.CommandServiceClusters.CommandPackageFrameworkServices;
import sh.zolt.cli.command.CommandServiceClusters.CommandRunFrameworkServices;
import sh.zolt.cli.command.CommandServiceClusters.CommandTestFrameworkServices;
import org.junit.jupiter.api.Test;

final class CommandServiceClustersTest {
    @Test
    void configEditServicesRequiresEveryCollaborator() {
        CommandConfigEditServices services = CommandFrameworkServices.configEditServices();

        assertRejectsNullCollaborators(
                () -> new CommandConfigEditServices(null, services.resolveService()),
                () -> new CommandConfigEditServices(services.manifests(), null));
    }

    @Test
    void buildFrameworkServicesRequiresEveryCollaborator() {
        CommandBuildFrameworkServices services = CommandFrameworkServices.buildFrameworkServices();

        assertRejectsNullCollaborators(
                () -> new CommandBuildFrameworkServices(
                        null,
                        services.resolveService()),
                () -> new CommandBuildFrameworkServices(
                        services.frameworkBuildAugmenter(),
                        null));
    }

    @Test
    void packageFrameworkServicesRequiresEveryCollaborator() {
        CommandPackageFrameworkServices services = CommandFrameworkServices.packageFrameworkServices();

        assertRejectsNullCollaborators(
                () -> new CommandPackageFrameworkServices(
                        null,
                        services.packagePlanService()),
                () -> new CommandPackageFrameworkServices(
                        services.packageAugmenter(),
                        null));
    }

    @Test
    void runFrameworkServicesRequiresEveryCollaborator() {
        CommandRunFrameworkServices services = CommandFrameworkServices.runFrameworkServices();

        assertRejectsNullCollaborators(
                () -> new CommandRunFrameworkServices(
                        null,
                        services.resolveService()),
                () -> new CommandRunFrameworkServices(
                        services.frameworkRunAugmenter(),
                        null));
    }

    @Test
    void testFrameworkServicesRequiresEveryCollaborator() {
        CommandTestFrameworkServices services = CommandFrameworkServices.testFrameworkServices();

        assertRejectsNullCollaborators(
                () -> new CommandTestFrameworkServices(
                        null,
                        services.resolveService()),
                () -> new CommandTestFrameworkServices(
                        services.frameworkTestRunner(),
                        null));
    }
}
