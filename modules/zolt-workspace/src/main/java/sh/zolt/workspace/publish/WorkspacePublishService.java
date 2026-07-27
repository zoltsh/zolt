package sh.zolt.workspace.publish;

import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.maven.repository.MavenRepositoryClient;
import sh.zolt.publish.CentralPortalClient;
import sh.zolt.publish.PublishCentralReadinessService;
import sh.zolt.publish.PublishDryRunService;
import sh.zolt.publish.PublishException;
import sh.zolt.publish.PublishSettingsReader;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
import sh.zolt.workspace.resolve.WorkspaceMemberPolicyResolver;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildService;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceSelection;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates {@code zolt publish --workspace}: a two-phase family publish.
 *
 * <p><strong>Phase 1 (offline).</strong> For every publishable member (in dependency order, BOM
 * last) it resolves the policy-merged config, projects a per-member publish lock (fact 6: directness
 * from config, versions from the aggregated lock), then reuses the normal single-project planner
 * ({@link PublishDryRunService#planResolved}) so every member carries the same sources/javadoc/SBOM/
 * checksum/signature plans, repository-credential and URL-safety policy as a single-project publish.
 * Inter-member completeness and per-member Central readiness are checked on top. Every blocker across
 * every member is aggregated into one family report; any blocker means nothing uploads.
 *
 * <p><strong>Phase 2.</strong> A plain repository receives a dependency-ordered sequential upload
 * (provider before consumer, BOM last) that authenticates every request and fails fast with an exact
 * resume command. Maven Central receives ONE atomic family bundle and one deployment id, polled to a
 * terminal state when {@code --wait} is set. The default enforces a uniform family version;
 * {@code allowMixedVersions} opts out.
 */
public final class WorkspacePublishService {
    private final WorkspaceBuildService workspaceBuildService;
    private final WorkspaceDiscoveryService workspaceDiscoveryService = new WorkspaceDiscoveryService();
    private final PublishSettingsReader publishSettingsReader;
    // Per-member Phase-1 planning (config merge, lock projection, single-project planner reuse,
    // inter-member and Central-readiness gates) is delegated so this orchestrator holds only the flow.
    private final WorkspaceMemberPlanner memberPlanner;
    private final WorkspaceRepositoryUploader uploader;
    private final WorkspaceCentralPublisher centralPublisher;
    private final WorkspacePublishStaging staging;

    public WorkspacePublishService() {
        this(new MavenRepositoryClient(), new CentralPortalClient(), new PackagePlanService());
    }

    public WorkspacePublishService(MavenRepositoryClient repositoryClient, CentralPortalClient portalClient) {
        this(repositoryClient, portalClient, new PackagePlanService());
    }

    /**
     * Composition-root constructor: the plain-repository uploader and the Central Portal client are
     * built from the CLI-configured {@link MavenRepositoryClient} / {@link CentralPortalClient} so
     * workspace uploads honour the same proxy, CA trust, and retry policy as single-project publishing.
     * {@code packagePlanService} carries the framework package-plan rules so each member's real primary
     * artifact is planned framework-aware (a Quarkus fast-jar's runner jar, a WAR's archive) rather than
     * synthesized as {@code <name>-<version>.jar}; the no-rules default suffices for plain jar members.
     */
    public WorkspacePublishService(
            MavenRepositoryClient repositoryClient,
            CentralPortalClient portalClient,
            PackagePlanService packagePlanService) {
        this(
                new WorkspaceBuildService(),
                new WorkspaceMemberPolicyResolver(),
                new WorkspaceMemberPomLockProjection(),
                new WorkspaceBomFamily(),
                new PublishSettingsReader(),
                new PublishCentralReadinessService(),
                new PublishDryRunService(packagePlanService),
                new WorkspaceRepositoryUploader(repositoryClient),
                new WorkspaceCentralPublisher(portalClient),
                packagePlanService);
    }

    WorkspacePublishService(
            WorkspaceBuildService workspaceBuildService,
            WorkspaceMemberPolicyResolver policyResolver,
            WorkspaceMemberPomLockProjection projection,
            WorkspaceBomFamily bomFamily,
            PublishSettingsReader publishSettingsReader,
            PublishCentralReadinessService centralReadinessService,
            PublishDryRunService dryRunService,
            WorkspaceRepositoryUploader uploader,
            WorkspaceCentralPublisher centralPublisher,
            PackagePlanService packagePlanService) {
        this.workspaceBuildService = workspaceBuildService;
        this.publishSettingsReader = publishSettingsReader;
        this.uploader = uploader;
        this.centralPublisher = centralPublisher;
        this.staging = new WorkspacePublishStaging();
        this.memberPlanner = new WorkspaceMemberPlanner(
                policyResolver,
                projection,
                bomFamily,
                publishSettingsReader,
                centralReadinessService,
                dryRunService,
                packagePlanService,
                new WorkspaceMemberSbomLockProjection(),
                new sh.zolt.workspace.service.WorkspaceClasspathService());
    }

    public WorkspacePublishReport publish(
            Path startDirectory, Path cacheRoot, WorkspaceSelectionRequest selectionRequest, Options options) {
        return publish(startDirectory, cacheRoot, selectionRequest, options, WorkspaceMemberSbomGenerator.disabled());
    }

    /**
     * Plans a single member's publish dry run when {@code startDirectory} IS a workspace member
     * directory, so {@code zolt publish --dry-run --central} works from inside a member. Members have no
     * member-level {@code zolt.lock}; this routes through the same per-member planning
     * ({@link WorkspaceMemberPlanner}) that {@code --workspace} uses, against the workspace-aggregated
     * root lock, and hands back the merged config and publish settings so the caller renders the
     * standard Central readiness checklist.
     *
     * <p>Family-scoped gates are deliberately not applied: inter-member completeness and uniform
     * versioning are what {@code --workspace} is for, and this preview answers only "is THIS member ready
     * for Central". Returns empty when the directory is not a workspace member (including the workspace
     * root itself), leaving standalone planning untouched.
     *
     * <p>{@code offline} is the caller's {@code --offline} flag and reaches the underlying build plan, so a
     * member dry run never reaches a repository when the user asked it not to. Lock freshness is the
     * caller's gate, exactly as it is for {@code --workspace}.
     */
    public Optional<WorkspaceMemberDryRun> planMemberDryRun(
            Path startDirectory,
            Path cacheRoot,
            boolean offline,
            boolean central,
            WorkspaceMemberSbomGenerator sbomGenerator) {
        Path directory = startDirectory.toAbsolutePath().normalize();
        Optional<WorkspaceMember> discovered = workspaceDiscoveryService.discover(directory)
                .filter(candidate -> !candidate.root().toAbsolutePath().normalize().equals(directory))
                .flatMap(candidate -> candidate.members().stream()
                        .filter(member -> member.directory().toAbsolutePath().normalize().equals(directory))
                        .findFirst());
        if (discovered.isEmpty()) {
            return Optional.empty();
        }
        String memberPath = discovered.orElseThrow().path();
        WorkspaceBuildPlan plan = workspaceBuildService.planBuild(
                directory, cacheRoot, offline, WorkspaceSelectionRequest.exact(List.of(memberPath)));
        WorkspaceMember member = plan.workspace().members().stream()
                .filter(candidate -> candidate.path().equals(memberPath))
                .findFirst()
                .orElseThrow();
        WorkspaceMemberPlanner.Planned planned = memberPlanner.planOffline(
                member, plan.workspace(), plan.lockfile(), cacheRoot, central, sbomGenerator);
        if (!planned.publish().configured()) {
            throw new PublishException("No [publish] configuration found. Add release/snapshot publish "
                    + "repositories before running `zolt publish --dry-run`.");
        }
        return Optional.of(new WorkspaceMemberDryRun(
                member.directory(),
                memberPath,
                WorkspaceMemberPlanner.coordinateString(planned.config()),
                planned.config(),
                planned.publish(),
                planned.plan()));
    }

    public WorkspacePublishReport publish(
            Path startDirectory,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest,
            Options options,
            WorkspaceMemberSbomGenerator sbomGenerator) {
        WorkspaceBuildPlan plan = workspaceBuildService.planBuild(startDirectory, cacheRoot, false, selectionRequest);
        Workspace workspace = plan.workspace();
        WorkspaceSelection selection = plan.selection();
        ZoltLockfile aggregatedLock = plan.lockfile();
        boolean resumeMode = selectionRequest.exact();

        // A `--resume-members` publish is backed by a durable transaction manifest, not a trusted hidden
        // flag: without matching v4 state we refuse rather than silently treat absent providers as
        // published, and a manifest from an older Zolt is refused outright rather than guessed at.
        Path statePath = WorkspacePublishPaths.resumeStatePath(workspace);
        ResumeState.ReadOutcome outcome = resumeMode ? ResumeState.read(statePath) : ResumeState.ReadOutcome.absent();
        if (resumeMode && !outcome.present()) {
            String display = WorkspacePublishPaths.displayPath(workspace, statePath);
            String message = outcome.legacy()
                    ? "the publish resume state at " + display + " was written with an older schema and "
                            + "cannot be trusted to resume this publish. Re-run the full publish: `zolt publish "
                            + "--workspace`."
                    : "no publish resume state found at " + display + ". `--resume-members` resumes a previously "
                            + "interrupted `zolt publish --workspace`; run the full publish instead: `zolt publish "
                            + "--workspace`.";
            return new WorkspacePublishReport(
                    List.of(), List.of(message), List.of(), false, Optional.empty(), Optional.empty(), Optional.empty());
        }
        Optional<ResumeState> resumeState = outcome.state();
        if (resumeState.isPresent()) {
            // The emitted command names only the failed tail, but the transaction manifest retains the
            // complete original family. Re-plan that family internally so completed providers are
            // re-staged from their recorded bytes and re-verified at their recorded targets before any
            // consumer or BOM can proceed.
            plan = workspaceBuildService.planBuild(
                    startDirectory,
                    cacheRoot,
                    false,
                    WorkspaceSelectionRequest.exact(resumeState.orElseThrow().familyMembers()));
            workspace = plan.workspace();
            selection = plan.selection();
            aggregatedLock = plan.lockfile();
        }

        List<WorkspaceMember> publishable =
                WorkspacePublishSelection.publishable(workspace, selection, publishSettingsReader);
        Set<String> publishSet = new LinkedHashSet<>();
        for (WorkspaceMember member : publishable) {
            publishSet.add(member.config().project().group() + ":" + member.config().project().name());
        }

        List<MemberPublication> publications = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (WorkspaceMember member : publishable) {
            WorkspaceMemberPlanner.Result result =
                    memberPlanner.plan(
                            member,
                            workspace,
                            aggregatedLock,
                            cacheRoot,
                            publishSet,
                            options,
                            resumeState,
                            sbomGenerator);
            publications.add(result.publication());
            blockers.addAll(result.blockers());
            notes.addAll(result.notes());
            if (resumeState.isPresent() && !selectionRequest.members().contains(member.path())) {
                notes.add("`"
                        + result.publication().coordinate()
                        + "` was already published by the interrupted attempt and was reverified at its recorded "
                        + "repository target.");
            }
        }
        if (!options.allowMixedVersions()) {
            blockers.addAll(WorkspacePublishSelection.uniformVersionBlockers(publishable));
        }
        if (options.central()) {
            blockers.addAll(WorkspaceCentralSettingsDivergence.blockers(publications));
        }

        // Phase-1 materialization of the plain-repository upload set before the first request: validate
        // every target (URL policy, credentials) and signing, and stage every checksum and signature —
        // only for a clean, live plain publish (Central assembles its own bundle; a dry run uploads none).
        List<StagedMember> stagedMembers = List.of();
        if (blockers.isEmpty() && !options.central() && !options.dryRun()) {
            WorkspacePublishStaging.Preparation preparation = staging.materialize(
                    publications, WorkspacePublishPaths.stagingRoot(workspace), options, resumeState);
            blockers.addAll(preparation.blockers());
            stagedMembers = preparation.members();
            // A resume is only honoured against state whose recorded plan still matches what would upload;
            // a changed plan (or a mismatched selection/options) refuses rather than upload stale bytes.
            if (blockers.isEmpty() && resumeState.isPresent()) {
                blockers.addAll(resumeState.orElseThrow().validate(stagedMembers, options, selectionRequest.members()));
            }
        }

        List<WorkspacePublishReport.Member> members = new ArrayList<>();
        for (MemberPublication publication : publications) {
            members.add(publication.toReportMember());
        }

        if (!blockers.isEmpty()) {
            return new WorkspacePublishReport(
                    members, blockers, notes, false, Optional.empty(), Optional.empty(), Optional.empty());
        }
        if (options.central()) {
            // One atomic family bundle, one deployment id — never per-member Central deployments.
            return centralPublisher.publish(workspace, publications, options).withNotes(notes);
        }
        if (options.dryRun()) {
            return new WorkspacePublishReport(
                    members, blockers, notes, false, Optional.empty(), Optional.empty(), Optional.empty());
        }
        Set<String> completed = resumeState.map(ResumeState::completed).orElse(Set.of());
        return uploader.upload(stagedMembers, options, completed, statePath).withNotes(notes);
    }

    /** Publish options mirroring the CLI flags. */
    public record Options(
            boolean dryRun, boolean central, boolean allowMixedVersions, boolean sbom, Optional<Duration> waitTimeout) {
        public Options {
            waitTimeout = waitTimeout == null ? Optional.empty() : waitTimeout;
        }

        public Options(boolean dryRun, boolean central, boolean allowMixedVersions, Optional<Duration> waitTimeout) {
            this(dryRun, central, allowMixedVersions, false, waitTimeout);
        }

        /**
         * Renders the exact resume command re-running only {@code members}, preserving the family-scoped
         * semantic options so a resume never silently drops them. Members are selected exactly (no
         * dependency expansion) through {@code --resume-members}. Render every family-scoped option from
         * this record here so a newly added one is structurally carried into the resume command.
         */
        String resumeCommand(List<String> members) {
            StringBuilder command = new StringBuilder("zolt publish --workspace");
            command.append(" --resume-members ").append(String.join(",", members));
            if (allowMixedVersions) {
                command.append(" --allow-mixed-versions");
            }
            if (sbom) {
                command.append(" --sbom");
            }
            return command.toString();
        }
    }
}
