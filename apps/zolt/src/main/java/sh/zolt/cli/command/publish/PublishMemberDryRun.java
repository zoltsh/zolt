package sh.zolt.cli.command.publish;

import sh.zolt.cli.command.CommandLockfiles;
import sh.zolt.publish.PublishCentralReadinessService;
import sh.zolt.publish.PublishCentralRequirement;
import sh.zolt.publish.PublishDryRunPlan;
import sh.zolt.workspace.publish.WorkspaceMemberDirectory;
import sh.zolt.workspace.publish.WorkspaceMemberDryRun;
import sh.zolt.workspace.publish.WorkspaceMemberSbomGenerator;
import sh.zolt.workspace.publish.WorkspacePublishService;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Routes {@code zolt publish --dry-run --central} to the workspace planner when it runs inside a
 * workspace member directory. Members have no member-level {@code zolt.lock}, so the standalone
 * planner has nothing to read there; the workspace planner builds the member's plan from the
 * aggregated root lock instead and the command renders the same Central readiness checklist.
 *
 * <p>Deliberately narrow: only the {@code --dry-run --central} path routes. A live member publish and
 * every non-Central dry run keep their standalone behaviour, and family publishing stays behind
 * {@code --workspace}. Outside a member directory this is {@link #absent()} and nothing changes.
 *
 * <p>Because this route plans against the workspace-aggregated root lock, it owes the same dependency
 * guarantees {@code zolt publish --workspace} gives: {@link #resolve} runs
 * {@link CommandLockfiles#requireFreshWorkspaceLockfile} before planning, so a stale root lock is refused
 * rather than silently planned against, and it threads {@code --offline} all the way into planning so the
 * flag is honoured from a member directory too.
 *
 * <p><strong>Membership decides first.</strong> That lock gate applies to any directory beneath a
 * discoverable workspace root, so it is asked only after {@link WorkspaceMemberDirectory} confirms this
 * directory IS a declared member. A standalone project nested in a workspace tree — its own
 * {@code zolt.toml}, its own {@code zolt.lock} — otherwise gets refused by a root lock governing members
 * it is not one of, which is exactly the "outside a member directory nothing changes" guarantee above.
 * The membership test reads config only, so ordering it first costs no lock read.
 */
final class PublishMemberDryRun {
    private final Optional<WorkspaceMemberDryRun> member;

    private PublishMemberDryRun(Optional<WorkspaceMemberDryRun> member) {
        this.member = member;
    }

    static PublishMemberDryRun absent() {
        return new PublishMemberDryRun(Optional.empty());
    }

    static PublishMemberDryRun resolve(
            boolean enabled,
            WorkspacePublishService workspacePublishService,
            CommandLockfiles lockfiles,
            Path projectRoot,
            Path cacheRoot,
            boolean offline,
            WorkspaceMemberSbomGenerator sbomGenerator) {
        if (!enabled || workspacePublishService.memberDirectory().at(projectRoot).isEmpty()) {
            return absent();
        }
        lockfiles.requireFreshWorkspaceLockfile(projectRoot, cacheRoot, offline, "zolt publish --dry-run --central");
        return new PublishMemberDryRun(
                workspacePublishService.planMemberDryRun(projectRoot, cacheRoot, offline, true, sbomGenerator));
    }

    boolean present() {
        return member.isPresent();
    }

    /** The member directory when routed, otherwise the caller's own project root. */
    Path root(Path projectRoot) {
        return member.map(WorkspaceMemberDryRun::memberDirectory).orElse(projectRoot);
    }

    PublishDryRunPlan plan(Supplier<PublishDryRunPlan> standalone) {
        return member.map(WorkspaceMemberDryRun::plan).orElseGet(standalone);
    }

    /**
     * Central readiness from the policy-merged member config and its publish settings, so the checklist
     * reflects the same inputs the plan was built from rather than a re-read of the raw member config.
     */
    List<PublishCentralRequirement> readiness(
            PublishCentralReadinessService readinessService,
            Path projectRoot,
            PublishDryRunPlan plan) {
        return member
                .map(present -> readinessService.evaluate(present.config(), present.publish(), plan))
                .orElseGet(() -> readinessService.evaluate(projectRoot, plan));
    }
}
