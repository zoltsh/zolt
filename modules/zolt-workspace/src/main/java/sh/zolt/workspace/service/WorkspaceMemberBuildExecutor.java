package sh.zolt.workspace.service;

import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.JavacException;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.incremental.IncrementalCompileSummary;
import sh.zolt.classpath.ClasspathSet;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Stage 1: runs only the members stage 0 could not rule out, and gives each its classpaths at the
 * moment the scheduler admits it.
 *
 * <p>A member that stage 0 declared clean never enters the ready queue. Its result is synthesized
 * from the state carried forward, which is what its queue trip would have produced anyway: the
 * output-existence assurance the clean path used to perform is now one recorded-state read in stage
 * 0 rather than a scheduled task, and members that genuinely need output finalization say so with a
 * reason and are admitted for exactly that.
 */
final class WorkspaceMemberBuildExecutor {
    private final BuildService buildService;
    private final WorkspaceJdkCheckerResolver jdkCheckers;
    private final WorkspaceBuildBatchPlanner batchPlanner;
    private final BuildCacheService buildCacheService;

    WorkspaceMemberBuildExecutor(
            BuildService buildService,
            WorkspaceJdkCheckerResolver jdkCheckers,
            WorkspaceBuildBatchPlanner batchPlanner) {
        this(buildService, jdkCheckers, batchPlanner, BuildCacheService.disabled());
    }

    private WorkspaceMemberBuildExecutor(
            BuildService buildService,
            WorkspaceJdkCheckerResolver jdkCheckers,
            WorkspaceBuildBatchPlanner batchPlanner,
            BuildCacheService buildCacheService) {
        this.buildService = buildService;
        this.jdkCheckers = jdkCheckers;
        this.batchPlanner = batchPlanner;
        this.buildCacheService = buildCacheService;
    }

    WorkspaceMemberBuildExecutor withJdkCheckers(WorkspaceJdkCheckerResolver jdkCheckers) {
        return new WorkspaceMemberBuildExecutor(buildService, jdkCheckers, batchPlanner, buildCacheService);
    }

    WorkspaceMemberBuildExecutor withBuildCache(BuildCacheService buildCacheService) {
        return new WorkspaceMemberBuildExecutor(buildService, jdkCheckers, batchPlanner, buildCacheService);
    }

    WorkspaceJdkCheckerResolver jdkCheckers() {
        return jdkCheckers;
    }

    Result build(
            WorkspaceExecutionContext context,
            Workspace workspace,
            WorkspaceSelection selection,
            Map<String, WorkspaceMember> membersByPath,
            WorkspaceMemberClasspaths classpaths,
            WorkspaceDirtyPlan dirtyPlan) {
        WorkspaceBuildBatchPlanner.Plan plan =
                batchPlanner.plan(workspace, selection.includedMembers());
        if (plan.includedMembers().isEmpty()) {
            return new Result(List.of(), 0, 0, 0L, 0, 0, Set.of(), 0, 0);
        }
        List<String> admitted = WorkspaceMemberAdmission.order(plan, dirtyPlan);
        Map<String, ScheduledMember> scheduled = new LinkedHashMap<>();
        int concurrency = 0;
        long schedulerIdleNanos = 0L;
        int readyQueuePeak = 0;
        if (!admitted.isEmpty()) {
            WorkspaceBuildBatchPlanner.Plan admittedPlan =
                    batchPlanner.plan(workspace, admitted, member -> sourceCount(dirtyPlan, member));
            concurrency = workspaceBuildConcurrency(admitted.size());
            WorkspaceReadyQueueExecutor.Result<ScheduledMember> execution =
                    new WorkspaceReadyQueueExecutor().execute(
                            admittedPlan,
                            concurrency,
                            (memberPath, dependencyInvalidated) -> executeMember(
                                    workspace,
                                    memberPath,
                                    dependencyInvalidated,
                                    membersByPath,
                                    classpaths,
                                    dirtyPlan,
                                    context));
            scheduled.putAll(execution.resultsByMember());
            schedulerIdleNanos = execution.schedulerIdleNanos();
            readyQueuePeak = execution.readyQueuePeak();
        }
        List<WorkspaceBuildResult.MemberBuildResult> orderedResults = new ArrayList<>();
        Set<String> executedMembers = new LinkedHashSet<>();
        int pipelineInvocations = 0;
        int finalizations = 0;
        for (String memberPath : selection.includedMembers()) {
            ScheduledMember member = scheduled.get(memberPath);
            if (member == null) {
                orderedResults.add(cleanResult(
                        memberPath,
                        membersByPath.get(memberPath),
                        dirtyPlan.member(memberPath),
                        0,
                        classpaths));
                continue;
            }
            orderedResults.add(member.result());
            if (member.pipelineInvoked() || member.finalized()) {
                executedMembers.add(memberPath);
            }
            pipelineInvocations += member.pipelineInvoked() ? 1 : 0;
            finalizations += member.finalized() ? 1 : 0;
        }
        return new Result(
                List.copyOf(orderedResults),
                plan.dependencyDepth(),
                concurrency,
                schedulerIdleNanos,
                readyQueuePeak,
                pipelineInvocations,
                Set.copyOf(executedMembers),
                finalizations,
                admitted.size());
    }

    private WorkspaceReadyQueueExecutor.TaskResult<ScheduledMember> executeMember(
            Workspace workspace,
            String memberPath,
            boolean dependencyInvalidated,
            Map<String, WorkspaceMember> membersByPath,
            WorkspaceMemberClasspaths classpaths,
            WorkspaceDirtyPlan dirtyPlan,
            WorkspaceExecutionContext context) {
        WorkspaceDirtyPlan.MemberPlan memberPlan = dirtyPlan.member(memberPath);
        WorkspaceMember member = membersByPath.get(memberPath);
        if (memberPlan.buildRequired() || dependencyInvalidated) {
            WorkspaceBuildResult.MemberBuildResult result =
                    buildMember(workspace, member, classpaths, context);
            String currentAbi = compileAbiDigest(context, member);
            return new WorkspaceReadyQueueExecutor.TaskResult<>(
                    new ScheduledMember(result, true, false),
                    !currentAbi.equals(memberPlan.previousCompileAbiDigest()));
        }
        if (memberPlan.finalizeRequired()) {
            return new WorkspaceReadyQueueExecutor.TaskResult<>(
                    new ScheduledMember(
                            finalizedResult(workspace, member, memberPlan, classpaths, context),
                            false,
                            true),
                    false);
        }
        return new WorkspaceReadyQueueExecutor.TaskResult<>(
                new ScheduledMember(
                        cleanResult(memberPath, member, memberPlan, 0, classpaths),
                        false,
                        false),
                false);
    }

    private WorkspaceBuildResult.MemberBuildResult buildMember(
            Workspace workspace,
            WorkspaceMember member,
            WorkspaceMemberClasspaths classpaths,
            WorkspaceExecutionContext context) {
        ClasspathSet memberClasspaths = classpaths.forMember(member.path());
        var memberPackages = classpaths.packagesForMember(member.path());
        try {
            return new WorkspaceBuildResult.MemberBuildResult(
                    member.path(),
                    buildService
                            .withJdkChecker(context
                                    .toolchainIndex()
                                    .checker(jdkCheckers, workspace, member))
                            .withBuildCache(buildCacheService)
                            .build(
                                    member.directory(),
                                    member.config(),
                                    memberClasspaths,
                                    memberPackages),
                    memberClasspaths,
                    memberPackages);
        } catch (JavacException exception) {
            throw new JavacException(
                    exception.getMessage()
                            + "\nWorkspace member `"
                            + member.path()
                            + "` failed to compile. If the missing type comes from a dependency of another workspace member, declare it directly in this member or move it to [dependencies.api] in the member that exposes it.",
                    exception);
        }
    }

    private WorkspaceBuildResult.MemberBuildResult finalizedResult(
            Workspace workspace,
            WorkspaceMember member,
            WorkspaceDirtyPlan.MemberPlan memberPlan,
            WorkspaceMemberClasspaths classpaths,
            WorkspaceExecutionContext context) {
        int finalizedOutputCount = buildService
                .withJdkChecker(context
                        .toolchainIndex()
                        .checker(jdkCheckers, workspace, member))
                .ensureCleanMemberOutputsCurrent(
                        member.directory(),
                        member.config(),
                        classpaths.forMember(member.path()));
        return cleanResult(
                member.path(),
                member,
                memberPlan,
                finalizedOutputCount,
                classpaths);
    }

    private static WorkspaceBuildResult.MemberBuildResult cleanResult(
            String memberPath,
            WorkspaceMember member,
            WorkspaceDirtyPlan.MemberPlan memberPlan,
            int finalizedOutputCount,
            WorkspaceMemberClasspaths classpaths) {
        Path outputDirectory = member.directory()
                .resolve(member.config().build().output())
                .toAbsolutePath()
                .normalize();
        return new WorkspaceBuildResult.MemberBuildResult(
                memberPath,
                new BuildResult(
                        Optional.empty(),
                        memberPlan.sourceCount(),
                        finalizedOutputCount,
                        outputDirectory,
                        "",
                        true),
                () -> classpaths.forMember(memberPath),
                () -> classpaths.packagesForMember(memberPath));
    }

    private static String compileAbiDigest(
            WorkspaceExecutionContext context,
            WorkspaceMember member) {
        Path output = member.directory()
                .resolve(member.config().build().output())
                .toAbsolutePath()
                .normalize();
        return context.abiIndex()
                .refreshMain(output)
                .map(IncrementalCompileSummary::compileAbiDigest)
                .orElse("");
    }

    /**
     * The scheduler's duration estimate. Source count is a proxy, not a measurement: nothing in the
     * persisted workspace state records how long a member took, so this is the best signal available
     * without adding a new state field. It only ever breaks ties between members whose critical paths
     * are the same length.
     */
    private static int sourceCount(WorkspaceDirtyPlan dirtyPlan, String member) {
        WorkspaceDirtyPlan.MemberPlan plan = dirtyPlan.member(member);
        return plan == null ? 0 : plan.sourceCount();
    }

    private static int workspaceBuildConcurrency(int memberCount) {
        if (memberCount <= 1) {
            return 1;
        }
        int processors = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(memberCount, processors));
    }

    record Result(
            List<WorkspaceBuildResult.MemberBuildResult> results,
            int waveCount,
            int maxWorkers,
            long schedulerIdleNanos,
            int readyQueuePeak,
            int pipelineInvocations,
            Set<String> executedMembers,
            int finalizations,
            int admitted) {
        Result(
                List<WorkspaceBuildResult.MemberBuildResult> results,
                int waveCount,
                int maxWorkers,
                long schedulerIdleNanos,
                int readyQueuePeak) {
            this(
                    results,
                    waveCount,
                    maxWorkers,
                    schedulerIdleNanos,
                    readyQueuePeak,
                    results.size(),
                    Set.of(),
                    0,
                    results.size());
        }

        Result(
                List<WorkspaceBuildResult.MemberBuildResult> results,
                int waveCount,
                int maxWorkers) {
            this(results, waveCount, maxWorkers, 0L, 0, results.size(), Set.of(), 0, results.size());
        }

        Result {
            results = List.copyOf(results);
            executedMembers = Set.copyOf(executedMembers);
        }
    }

    private record ScheduledMember(
            WorkspaceBuildResult.MemberBuildResult result,
            boolean pipelineInvoked,
            boolean finalized) {
    }
}
