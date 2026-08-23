package sh.zolt.quality;

import java.util.Optional;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.member.MemberResolvedView;
import sh.zolt.workspace.service.WorkspaceMember;

/**
 * One member as the workspace quality checks see it: the shared {@link MemberResolvedView} plus the
 * package plan {@code package-contents} needs, which is a packaging concern rather than a view of the
 * lock. The lock-shaped accessors delegate, so a check reads the same projection every other
 * member-facing command reads.
 */
record WorkspaceMemberQualityView(
        WorkspaceMember member,
        MemberResolvedView view,
        Optional<PackagePlan> packagePlan) {

    ProjectConfig effectiveConfig() {
        return view.effectiveConfig();
    }

    /** The member's all-scope policy view, for dependency-metadata and dependency-policy checks. */
    ZoltLockfile policyLock() {
        return view.policyLock();
    }

    /** The member's full reachable closure, for license-policy evaluation. */
    ZoltLockfile sbomLock() {
        return view.dependencyGraphLock();
    }
}
