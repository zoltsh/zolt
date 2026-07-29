package sh.zolt.workspace.service;

import sh.zolt.build.BuildService;
import sh.zolt.build.JavacException;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    Result build(
            Workspace workspace,
            WorkspaceSelection selection,
            Map<String, WorkspaceMember> membersByPath,
            Map<String, ClasspathSet> classpathsByMember,
            Map<String, List<ResolvedClasspathPackage>> classpathPackagesByMember) {
        WorkspaceBuildBatchPlanner.Plan plan =
                batchPlanner.plan(workspace, selection.includedMembers());
        if (plan.includedMembers().isEmpty()) {
            return new Result(List.of(), 0, 0, 0L, 0);
        }
        int concurrency = workspaceBuildConcurrency(selection.includedMembers().size());
        WorkspaceReadyQueueExecutor.Result<WorkspaceBuildResult.MemberBuildResult> execution =
                new WorkspaceReadyQueueExecutor().execute(
                        plan,
                        concurrency,
                        memberPath -> buildMember(
                                workspace,
                                memberPath,
                                membersByPath,
                                classpathsByMember,
                                classpathPackagesByMember));
        List<WorkspaceBuildResult.MemberBuildResult> orderedResults = new ArrayList<>();
        for (String memberPath : selection.includedMembers()) {
            orderedResults.add(execution.resultsByMember().get(memberPath));
        }
        return new Result(
                List.copyOf(orderedResults),
                plan.dependencyDepth(),
                concurrency,
                execution.schedulerIdleNanos(),
                execution.readyQueuePeak());
    }

    private WorkspaceBuildResult.MemberBuildResult buildMember(
            Workspace workspace,
            String memberPath,
            Map<String, WorkspaceMember> membersByPath,
            Map<String, ClasspathSet> classpathsByMember,
            Map<String, List<ResolvedClasspathPackage>> classpathPackagesByMember) {
        WorkspaceMember member = membersByPath.get(memberPath);
        ClasspathSet classpaths = classpathsByMember.get(member.path());
        try {
            return new WorkspaceBuildResult.MemberBuildResult(
                    member.path(),
                    buildService
                            .withJdkChecker(jdkCheckers.forMember(workspace, member))
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
            int readyQueuePeak) {
        Result(
                List<WorkspaceBuildResult.MemberBuildResult> results,
                int waveCount,
                int maxWorkers) {
            this(results, waveCount, maxWorkers, 0L, 0);
        }

        Result {
            results = List.copyOf(results);
        }
    }
}
