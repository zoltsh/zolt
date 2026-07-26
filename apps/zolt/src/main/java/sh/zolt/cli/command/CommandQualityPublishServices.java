package sh.zolt.cli.command;

import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.cli.command.CommandServiceBundles.CommandPublishServices;
import sh.zolt.cli.command.CommandServiceBundles.CommandQualityServices;
import sh.zolt.cli.net.CommandNetwork;
import sh.zolt.publish.CentralPortalClient;
import sh.zolt.publish.PublishDryRunService;
import sh.zolt.quality.QualityCheckComposition;
import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.publish.WorkspacePublishService;
import sh.zolt.workspace.resolve.WorkspaceResolveService;

final class CommandQualityPublishServices {
    private CommandQualityPublishServices() {}

    static CommandQualityServices qualityCommandServices() {
        PackagePlanService packagePlanService =
                CommandFrameworkServices.packagePlanService();
        ResolveService resolveService =
                CommandFrameworkServices.resolveService();
        PublishDryRunService publishDryRunService =
                new PublishDryRunService(packagePlanService);
        WorkspacePublishService workspacePublishService =
                workspacePublishService(packagePlanService);
        return new CommandQualityServices(QualityCheckComposition.create(
                packagePlanService,
                resolveService,
                new WorkspaceResolveService(resolveService),
                publishDryRunService,
                workspacePublishService));
    }

    static CommandPublishServices publishCommandServices() {
        PackagePlanService packagePlanService =
                CommandFrameworkServices.packagePlanService();
        return new CommandPublishServices(
                new PublishDryRunService(packagePlanService),
                workspacePublishService(packagePlanService));
    }

    private static WorkspacePublishService workspacePublishService(
            PackagePlanService packagePlanService) {
        return new WorkspacePublishService(
                CommandNetwork.repositoryClient(),
                new CentralPortalClient(CommandNetwork.defaultTransport()),
                packagePlanService);
    }
}
