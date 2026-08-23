package sh.zolt.cli.command.publish;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import sh.zolt.cli.command.CommandLockfiles;
import sh.zolt.project.ProjectConfig;
import sh.zolt.publish.PublishCentralReadinessService;
import sh.zolt.publish.PublishCentralRequirement;
import sh.zolt.publish.PublishDryRunPlan;
import sh.zolt.publish.PublishSettings;
import sh.zolt.workspace.publish.WorkspaceMemberDirectory;
import sh.zolt.workspace.publish.WorkspaceMemberDryRun;
import sh.zolt.workspace.publish.WorkspaceMemberSbomGenerator;
import sh.zolt.workspace.publish.WorkspacePublishService;

/**
 * Routes {@code zolt publish} to the workspace planner whenever it runs inside a workspace member
 * directory — in EVERY mode, not one.
 *
 * <p>Members have no member-level {@code zolt.lock}, so the standalone planner has nothing to read
 * there; the workspace planner builds the member's plan from the aggregated root lock instead. That
 * was true of the Central dry run when it was the only routed mode, and it is equally true of a plain
 * repository dry run, a plain live upload, a Central live upload, an attached SBOM, and a release-policy
 * preflight. A publication is the most consequential thing a member command emits: a POM listing a
 * sibling's dependencies, or an SBOM attesting to packages this artifact never contained, is wrong
 * evidence that outlives the command. One route means every mode publishes the same bytes.
 *
 * <p>Outside a member directory this is {@link #absent()} and nothing changes: a standalone project
 * keeps the standalone planner and its own lock.
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
 *
 * <p><strong>Family scope stays behind {@code --workspace}.</strong> This routes ONE member: it applies
 * no inter-member completeness gate and no uniform-version rule, because those are answers about a
 * family and this command was asked about a member.
 */
final class PublishMemberRoute {
    private final Optional<WorkspaceMemberDryRun> member;

    private PublishMemberRoute(Optional<WorkspaceMemberDryRun> member) {
        this.member = member;
    }

    static PublishMemberRoute absent() {
        return new PublishMemberRoute(Optional.empty());
    }

    /**
     * @param central whether this invocation targets Maven Central, which decides whether a configured
     *     internal repository is required — the same distinction the standalone planner draws
     * @param command the exact user-facing invocation, so a stale-lock refusal names what was run
     */
    static PublishMemberRoute resolve(
            WorkspacePublishService workspacePublishService,
            CommandLockfiles lockfiles,
            Path projectRoot,
            Path cacheRoot,
            boolean offline,
            boolean central,
            String command,
            WorkspaceMemberSbomGenerator sbomGenerator) {
        if (workspacePublishService.memberDirectory().at(projectRoot).isEmpty()) {
            return absent();
        }
        lockfiles.requireFreshWorkspaceLockfile(projectRoot, cacheRoot, offline, command);
        return new PublishMemberRoute(
                workspacePublishService.planMemberDryRun(
                        projectRoot, cacheRoot, offline, central, sbomGenerator));
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

    /** The policy-merged member config the plan was built from. */
    Optional<ProjectConfig> config() {
        return member.map(WorkspaceMemberDryRun::config);
    }

    /** The member's publish settings as the planner read them. */
    Optional<PublishSettings> publish() {
        return member.map(WorkspaceMemberDryRun::publish);
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
