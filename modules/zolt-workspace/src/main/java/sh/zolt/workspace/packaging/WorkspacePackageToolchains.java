package sh.zolt.workspace.packaging;

import sh.zolt.build.packaging.PackageService;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceJdkCheckerResolver;

/** Rejects unavailable documentation tools before any selected member output can change. */
final class WorkspacePackageToolchains {
    private WorkspacePackageToolchains() {}

    static void preflight(WorkspaceBuildPlan plan, PackageService packages, WorkspaceJdkCheckerResolver checkers) {
        for (var member : plan.workspace().members()) {
            if (plan.selection().selectedMembers().contains(member.path())) {
                packages.withJdkChecker(checkers.forMember(plan.workspace(), member))
                        .preflightSupplementalTools(member.config());
            }
        }
    }
}
