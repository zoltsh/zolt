package sh.zolt.workspace.publish;

import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.publish.PublishCentralReadinessService;
import sh.zolt.publish.PublishCentralRequirement;
import sh.zolt.publish.PublishDryRunPlan;
import sh.zolt.publish.PublishDryRunService;
import sh.zolt.publish.PublishInterMemberGuard;
import sh.zolt.publish.PublishSettings;
import sh.zolt.publish.ManifestPublishSettingsLoader;
import sh.zolt.workspace.member.MemberResolvedView;
import sh.zolt.workspace.member.MemberResolvedViewService;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Builds one publishable member's {@link MemberPublication} for {@code zolt publish --workspace} and
 * for every member-directory {@code zolt publish} mode: it takes the member's
 * {@link MemberResolvedView} of the workspace lock, then reuses the single-project planner
 * ({@link PublishDryRunService#planResolved}) so the member carries the same sources/javadoc/SBOM/
 * checksum/signature plans and repository-credential/URL-safety policy as a single-project publish.
 * Inter-member completeness and per-member Central readiness are layered on top; on a resume, an
 * absent inter-member provider is a note only when the durable state proves it already published, a
 * blocker otherwise. Extracted from {@link WorkspacePublishService} so the orchestrator holds only the
 * two-phase flow and this file-size budget.
 *
 * <p>The lock projections are not this class's own: {@link MemberResolvedViewService} owns them for
 * the whole member-facing command matrix, so a member's published POM lists exactly the roots
 * {@code zolt check} evaluates policy over and its attached SBOM describes exactly the closure
 * {@code zolt sbom} prints.
 */
final class WorkspaceMemberPlanner {
    private final MemberResolvedViewService viewService;
    private final ManifestPublishSettingsLoader publishSettingsLoader;
    private final PublishCentralReadinessService centralReadinessService;
    private final PublishDryRunService dryRunService;
    private final PackagePlanService packagePlanService;

    WorkspaceMemberPlanner(
            MemberResolvedViewService viewService,
            ManifestPublishSettingsLoader publishSettingsLoader,
            PublishCentralReadinessService centralReadinessService,
            PublishDryRunService dryRunService,
            PackagePlanService packagePlanService) {
        this.viewService = viewService;
        this.publishSettingsLoader = publishSettingsLoader;
        this.centralReadinessService = centralReadinessService;
        this.dryRunService = dryRunService;
        this.packagePlanService = packagePlanService;
    }

    Result plan(
            WorkspaceMember member,
            Workspace workspace,
            ZoltLockfile aggregatedLock,
            Path cacheRoot,
            Set<String> publishSet,
            WorkspacePublishService.Options options,
            Optional<ResumeState> resumeState,
            WorkspaceMemberSbomGenerator sbomGenerator) {
        Planned planned =
                planOffline(member, workspace, aggregatedLock, cacheRoot, options.central(), sbomGenerator);
        ProjectConfig config = planned.config();
        PublishSettings publish = planned.publish();
        PublishDryRunPlan memberPlan = planned.plan();

        List<String> extraBlockers = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (!planned.bom()) {
            for (String sibling : PublishInterMemberGuard.missingSiblings(planned.memberLock(), publishSet)) {
                if (resumeState.isPresent()) {
                    // A resumed set omits providers only when the state proves they landed; an absent
                    // sibling the state does not record as published is an incomplete family, not a note.
                    if (resumeState.orElseThrow().recordsPublished(sibling)) {
                        notes.add("inter-member dependency `" + sibling + "` of `" + coordinateString(config)
                                + "` is absent from the resumed set and recorded as already published; "
                                + "treating it as satisfied.");
                    } else {
                        extraBlockers.add("inter-member dependency `" + sibling + "` of `" + coordinateString(config)
                                + "` was never uploaded in the interrupted publish; re-run the full publish: "
                                + "`zolt publish --workspace`.");
                    }
                } else {
                    extraBlockers.add("inter-member dependency `" + sibling + "` of `" + coordinateString(config)
                            + "` is not in the publish set; publish the family together or add `--member` for it.");
                }
            }
        }
        if (options.central()) {
            for (PublishCentralRequirement requirement :
                    centralReadinessService.evaluate(config, publish, memberPlan)) {
                if (!requirement.satisfied()) {
                    extraBlockers.add(
                            coordinateString(config) + ": " + requirement.name() + " — " + requirement.remediation());
                }
            }
        }
        PublishDryRunPlan finalPlan =
                extraBlockers.isEmpty() ? memberPlan : memberPlan.withContext(memberPlan.context(), extraBlockers);
        MemberPublication publication = new MemberPublication(
                member.directory(),
                member.path(),
                coordinateString(config),
                planned.bom(),
                finalPlan,
                publish,
                config.repositoryCredentials());
        return new Result(publication, finalPlan.blockers(), notes);
    }

    /**
     * The offline half of a member publish plan: policy-merged config, projected member lock, package
     * plan, SBOM, and the single-project planner's own blockers — with no family-scoped gate applied.
     * {@link #plan} layers inter-member and Central-readiness blockers on top; a member-directory
     * Central dry run instead renders readiness as its own checklist, so it consumes this directly.
     */
    Planned planOffline(
            WorkspaceMember member,
            Workspace workspace,
            ZoltLockfile aggregatedLock,
            Path cacheRoot,
            boolean central,
            WorkspaceMemberSbomGenerator sbomGenerator) {
        return planOffline(
                viewService.view(
                        workspace,
                        aggregatedLock,
                        member,
                        MemberResolvedViewService.authoritativeLockfile(workspace)),
                cacheRoot,
                central,
                sbomGenerator);
    }

    /** As above, for a caller that already projected the member's view of the workspace lock. */
    Planned planOffline(
            MemberResolvedView view,
            Path cacheRoot,
            boolean central,
            WorkspaceMemberSbomGenerator sbomGenerator) {
        ProjectConfig config = view.effectiveConfig();
        boolean bom = view.bom();
        ZoltLockfile memberLock = view.publicationLock();
        PublishSettings publish =
                publishSettingsLoader.read(view.memberDirectory().resolve("zolt.toml"));
        // Resolve the member's REAL primary artifact through the framework-aware package planner (the
        // same path single-project publishing plans) — a Quarkus fast-jar's quarkus-run.jar or any
        // future mode's real archive, not a synthesized <name>-<version>.jar. The lock only feeds the
        // planner's discarded dependency listing; the archive path derives from the member dir + config.
        PackagePlan packagePlan =
                packagePlanService.plan(
                        view.memberDirectory(),
                        config,
                        view.packageLock(),
                        cacheRoot);
        // The POM plan below consumes the POM-shaped publication lock; the SBOM consumes the full
        // dependency-graph closure. Both are the member's own slice of the one workspace resolution.
        Optional<Path> sbomFile = bom
                ? Optional.empty()
                : sbomGenerator.generate(view.memberDirectory(), config, view.dependencyGraphLock());

        // Reuse the single-project planner against the projected member lock: this is the sole source
        // of the member's supplemental/SBOM/checksum plans and its credential + URL-safety blockers.
        PublishDryRunPlan memberPlan = dryRunService.planResolved(
                view.memberDirectory(),
                config,
                publish,
                () -> memberLock,
                () -> packagePlan,
                !central,
                sbomFile);
        return new Planned(config, publish, memberLock, bom, memberPlan);
    }

    static String coordinateString(ProjectConfig config) {
        return config.project().group() + ":" + config.project().name() + ":" + config.project().version();
    }

    /** One member's offline plan inputs and result, before any family-scoped blocker is layered on. */
    record Planned(
            ProjectConfig config,
            PublishSettings publish,
            ZoltLockfile memberLock,
            boolean bom,
            PublishDryRunPlan plan) {
    }

    /** One member's planned publication plus its aggregated blockers and non-blocking notes. */
    record Result(MemberPublication publication, List<String> blockers, List<String> notes) {
    }
}
