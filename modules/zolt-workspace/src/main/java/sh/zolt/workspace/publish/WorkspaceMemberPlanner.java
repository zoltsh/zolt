package sh.zolt.workspace.publish;

import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.publish.PublishCentralReadinessService;
import sh.zolt.publish.PublishCentralRequirement;
import sh.zolt.publish.PublishDryRunPlan;
import sh.zolt.publish.PublishDryRunService;
import sh.zolt.publish.PublishInterMemberGuard;
import sh.zolt.publish.PublishSettings;
import sh.zolt.publish.ManifestPublishSettingsLoader;
import sh.zolt.workspace.resolve.WorkspaceMemberPolicyResolver;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceClasspathService;
import sh.zolt.workspace.service.WorkspaceMember;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Builds one publishable member's {@link MemberPublication} for {@code zolt publish --workspace}: it
 * resolves the policy-merged config, projects the per-member publish lock (fact 6: directness from
 * config, versions from the aggregated lock), then reuses the single-project planner
 * ({@link PublishDryRunService#planResolved}) so the member carries the same sources/javadoc/SBOM/
 * checksum/signature plans and repository-credential/URL-safety policy as a single-project publish.
 * Inter-member completeness and per-member Central readiness are layered on top; on a resume, an
 * absent inter-member provider is a note only when the durable state proves it already published, a
 * blocker otherwise. Extracted from {@link WorkspacePublishService} so the orchestrator holds only the
 * two-phase flow and this file-size budget.
 */
final class WorkspaceMemberPlanner {
    private final WorkspaceMemberPolicyResolver policyResolver;
    private final WorkspaceMemberPomLockProjection projection;
    private final WorkspaceBomFamily bomFamily;
    private final ManifestPublishSettingsLoader publishSettingsLoader;
    private final PublishCentralReadinessService centralReadinessService;
    private final PublishDryRunService dryRunService;
    private final PackagePlanService packagePlanService;
    private final WorkspaceMemberSbomLockProjection sbomProjection;
    private final WorkspaceClasspathService classpathService;

    WorkspaceMemberPlanner(
            WorkspaceMemberPolicyResolver policyResolver,
            WorkspaceMemberPomLockProjection projection,
            WorkspaceBomFamily bomFamily,
            ManifestPublishSettingsLoader publishSettingsLoader,
            PublishCentralReadinessService centralReadinessService,
            PublishDryRunService dryRunService,
            PackagePlanService packagePlanService,
            WorkspaceMemberSbomLockProjection sbomProjection,
            WorkspaceClasspathService classpathService) {
        this.policyResolver = policyResolver;
        this.projection = projection;
        this.bomFamily = bomFamily;
        this.publishSettingsLoader = publishSettingsLoader;
        this.centralReadinessService = centralReadinessService;
        this.dryRunService = dryRunService;
        this.packagePlanService = packagePlanService;
        this.sbomProjection = sbomProjection;
        this.classpathService = classpathService;
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
        ProjectConfig config = policyResolver.merge(workspace, member);
        boolean bom = config.packageSettings().mode() == PackageMode.BOM;
        ZoltLockfile memberLock = bom
                ? bomFamily.familyLock(workspace, aggregatedLock, member)
                : projection.project(member.path(), config, aggregatedLock);
        ZoltLockfile packageLock = bom
                ? memberLock
                : classpathService
                        .packageLocksForMembers(
                                workspace,
                                aggregatedLock,
                                List.of(member.path()))
                        .get(member.path());
        PublishSettings publish =
                publishSettingsLoader.read(member.directory().resolve("zolt.toml"));
        // Resolve the member's REAL primary artifact through the framework-aware package planner (the
        // same path single-project publishing plans) — a Quarkus fast-jar's quarkus-run.jar or any
        // future mode's real archive, not a synthesized <name>-<version>.jar. The lock only feeds the
        // planner's discarded dependency listing; the archive path derives from the member dir + config.
        PackagePlan packagePlan =
                packagePlanService.plan(
                        member.directory(),
                        config,
                        packageLock,
                        cacheRoot);
        // The POM plan below consumes the POM-shaped memberLock; the SBOM consumes the full closure.
        Optional<Path> sbomFile = bom
                ? Optional.empty()
                : sbomGenerator.generate(member.directory(), config,
                        sbomProjection.project(member.path(), config, aggregatedLock, workspace, policyResolver));

        // Reuse the single-project planner against the projected member lock: this is the sole source
        // of the member's supplemental/SBOM/checksum plans and its credential + URL-safety blockers.
        PublishDryRunPlan memberPlan = dryRunService.planResolved(
                member.directory(),
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
