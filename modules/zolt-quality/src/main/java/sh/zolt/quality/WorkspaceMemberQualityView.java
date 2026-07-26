package sh.zolt.quality;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.service.WorkspaceMember;

/**
 * The single member-qualified model shared by workspace dependency, license, and policy checks.
 */
record WorkspaceMemberQualityView(
        WorkspaceMember member,
        ProjectConfig effectiveConfig,
        ZoltLockfile policyLock,
        ZoltLockfile sbomLock) {
}
