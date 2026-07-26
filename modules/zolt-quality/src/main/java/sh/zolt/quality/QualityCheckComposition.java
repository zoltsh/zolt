package sh.zolt.quality;

import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.publish.PublishDryRunService;
import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.publish.WorkspacePublishService;
import sh.zolt.workspace.resolve.WorkspaceResolveService;

/**
 * Builds quality services with the application-owned resolution, packaging, and publishing collaborators.
 */
public final class QualityCheckComposition {
    private QualityCheckComposition() {}

    public static QualityCheckService create(
            PackagePlanService packagePlanService,
            ResolveService resolveService,
            WorkspaceResolveService workspaceResolveService,
            PublishDryRunService publishDryRunService,
            WorkspacePublishService workspacePublishService) {
        return new QualityCheckService(QualityCheckDependencies.create(
                System::getenv,
                packagePlanService,
                resolveService,
                workspaceResolveService,
                publishDryRunService,
                workspacePublishService));
    }
}
