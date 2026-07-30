package sh.zolt.workspace.service;

import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.JavacException;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.incremental.IncrementalCompileSummary;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
            Map<String, ClasspathSet> classpathsByMember,
            Map<String, List<ResolvedClasspathPackage>> classpathPackagesByMember,
            WorkspaceDirtyPlan dirtyPlan) {
        WorkspaceBuildBatchPlanner.Plan plan =
                batchPlanner.plan(workspace, selection.includedMembers());
        if (plan.includedMembers().isEmpty()) {
            return new Result(List.of(), 0, 0, 0L, 0, 0);
        }
        int concurrency = workspaceBuildConcurrency(selection.includedMembers().size());
        WorkspaceReadyQueueExecutor.Result<ScheduledMember> execution =
                new WorkspaceReadyQueueExecutor().execute(
                        plan,
                        concurrency,
                        (memberPath, dependencyInvalidated) -> buildOrReuseMember(
                                workspace,
                                memberPath,
                                dependencyInvalidated,
                                membersByPath,
                                classpathsByMember,
                                classpathPackagesByMember,
                                dirtyPlan,
                                context));
        List<WorkspaceBuildResult.MemberBuildResult> orderedResults = new ArrayList<>();
        for (String memberPath : selection.includedMembers()) {
            orderedResults.add(execution.resultsByMember().get(memberPath).result());
        }
        int pipelineInvocations = (int) execution.resultsByMember().values().stream()
                .filter(ScheduledMember::pipelineInvoked)
                .count();
        return new Result(
                List.copyOf(orderedResults),
                plan.dependencyDepth(),
                concurrency,
                execution.schedulerIdleNanos(),
                execution.readyQueuePeak(),
                pipelineInvocations);
    }

    private WorkspaceReadyQueueExecutor.TaskResult<ScheduledMember>
            buildOrReuseMember(
                    Workspace workspace,
                    String memberPath,
                    boolean dependencyInvalidated,
                    Map<String, WorkspaceMember> membersByPath,
                    Map<String, ClasspathSet> classpathsByMember,
                    Map<String, List<ResolvedClasspathPackage>> classpathPackagesByMember,
                    WorkspaceDirtyPlan dirtyPlan,
                    WorkspaceExecutionContext context) {
        WorkspaceDirtyPlan.MemberPlan memberPlan = dirtyPlan.member(memberPath);
        boolean invokeBuild = memberPlan.buildRequired() || dependencyInvalidated;
        WorkspaceBuildResult.MemberBuildResult result = invokeBuild
                ? buildMember(
                        workspace,
                        memberPath,
                        membersByPath,
                        classpathsByMember,
                        classpathPackagesByMember,
                        context)
                : cleanMember(
                        workspace,
                        memberPath,
                        membersByPath,
                        classpathsByMember,
                        classpathPackagesByMember,
                        memberPlan,
                        context);
        String currentAbi = compileAbiDigest(
                context,
                membersByPath.get(memberPath),
                invokeBuild);
        boolean abiChanged = invokeBuild
                && !currentAbi.equals(memberPlan.previousCompileAbiDigest());
        return new WorkspaceReadyQueueExecutor.TaskResult<>(
                new ScheduledMember(result, invokeBuild),
                abiChanged);
    }

    private WorkspaceBuildResult.MemberBuildResult buildMember(
            Workspace workspace,
            String memberPath,
            Map<String, WorkspaceMember> membersByPath,
            Map<String, ClasspathSet> classpathsByMember,
            Map<String, List<ResolvedClasspathPackage>> classpathPackagesByMember,
            WorkspaceExecutionContext context) {
        WorkspaceMember member = membersByPath.get(memberPath);
        ClasspathSet classpaths = classpathsByMember.get(member.path());
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
                                    classpaths),
                    classpaths,
                    classpathPackagesByMember.get(member.path()));
        } catch (JavacException exception) {
            throw new JavacException(
                    exception.getMessage()
                            + "\nWorkspace member `"
                            + member.path()
                            + "` failed to compile. If the missing type comes from a dependency of another workspace member, declare it directly in this member or move it to [api.dependencies] in the member that exposes it.",
                    exception);
        }
    }

    private WorkspaceBuildResult.MemberBuildResult cleanMember(
            Workspace workspace,
            String memberPath,
            Map<String, WorkspaceMember> membersByPath,
            Map<String, ClasspathSet> classpathsByMember,
            Map<String, List<ResolvedClasspathPackage>> classpathPackagesByMember,
            WorkspaceDirtyPlan.MemberPlan memberPlan,
            WorkspaceExecutionContext context) {
        WorkspaceMember member = membersByPath.get(memberPath);
        Path outputDirectory = member.directory()
                .resolve(member.config().build().output())
                .toAbsolutePath()
                .normalize();
        ClasspathSet classpaths = classpathsByMember.get(memberPath);
        int finalizedOutputCount = buildService
                .withJdkChecker(context
                        .toolchainIndex()
                        .checker(jdkCheckers, workspace, member))
                .ensureCleanMemberOutputsCurrent(
                        member.directory(),
                        member.config(),
                        classpaths);
        return new WorkspaceBuildResult.MemberBuildResult(
                memberPath,
                new BuildResult(
                        Optional.empty(),
                        memberPlan.sourceCount(),
                        finalizedOutputCount,
                        outputDirectory,
                        "",
                        true),
                classpaths,
                classpathPackagesByMember.get(memberPath));
    }

    private static String compileAbiDigest(
            WorkspaceExecutionContext context,
            WorkspaceMember member,
            boolean refresh) {
        Path output = member.directory()
                .resolve(member.config().build().output())
                .toAbsolutePath()
                .normalize();
        Optional<IncrementalCompileSummary> summary = refresh
                ? context.abiIndex().refreshMain(output)
                : context.abiIndex().main(output);
        return summary
                .map(IncrementalCompileSummary::compileAbiDigest)
                .orElse("");
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
            int pipelineInvocations) {
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
                    results.size());
        }

        Result(
                List<WorkspaceBuildResult.MemberBuildResult> results,
                int waveCount,
                int maxWorkers) {
            this(results, waveCount, maxWorkers, 0L, 0, results.size());
        }

        Result {
            results = List.copyOf(results);
        }
    }

    private record ScheduledMember(
            WorkspaceBuildResult.MemberBuildResult result,
            boolean pipelineInvoked) {
    }
}
