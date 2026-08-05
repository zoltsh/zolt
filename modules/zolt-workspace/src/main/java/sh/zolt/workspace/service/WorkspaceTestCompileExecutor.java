package sh.zolt.workspace.service;

import sh.zolt.build.BuildException;
import sh.zolt.build.BuildResultWithClasspaths;
import sh.zolt.build.testruntime.TestRunService;
import sh.zolt.build.testruntime.compile.TestCompileResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class WorkspaceTestCompileExecutor {
    private final WorkspaceTestRunServiceResolver testRunServices;

    WorkspaceTestCompileExecutor(WorkspaceTestRunServiceResolver testRunServices) {
        this.testRunServices = testRunServices;
    }

    WorkspaceTestCompileResult compile(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult) {
        Workspace workspace = plan.workspace();
        WorkspaceSelection selection = plan.selection();
        Map<String, WorkspaceMember> membersByPath = membersByPath(workspace);
        Map<String, WorkspaceBuildResult.MemberBuildResult> buildsByPath = buildsByPath(buildResult);
        int concurrency = workspaceTestConcurrency(selection.selectedMembers().size());
        Map<String, WorkspaceTestCompileResult.MemberTestCompileResult> current = new LinkedHashMap<>();
        Map<String, Future<WorkspaceTestCompileResult.MemberTestCompileResult>> futures = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(concurrency)) {
            for (String memberPath : selection.selectedMembers()) {
                WorkspaceMember member = membersByPath.get(memberPath);
                if (!buildResult.membersRequiringTestCompile().contains(memberPath)) {
                    current.put(
                            memberPath,
                            currentTestClasses(plan, member, buildsByPath.get(memberPath)));
                    continue;
                }
                futures.put(memberPath, executor.submit(compileTestMember(
                        workspace,
                        member,
                        buildsByPath.get(memberPath))));
            }
            List<WorkspaceTestCompileResult.MemberTestCompileResult> results = new ArrayList<>();
            for (String memberPath : selection.selectedMembers()) {
                results.add(current.containsKey(memberPath)
                        ? current.get(memberPath)
                        : getTestCompileResult(futures.get(memberPath)));
            }
            return new WorkspaceTestCompileResult(
                    buildResult.resolveResult(),
                    buildResult.members(),
                    results,
                    workspace.members().size(),
                    concurrency,
                    WorkspaceTestToolchainMetrics.combine(
                            buildResult.executionMetrics(),
                            testRunServices.toolchainMetrics()));
        }
    }

    /**
     * The member's test classes are already current, and saying so costs one recorded state read —
     * where compiling would first have had to project the member's whole test classpath out of the
     * root lock just to reach the same conclusion.
     */
    private static WorkspaceTestCompileResult.MemberTestCompileResult currentTestClasses(
            WorkspaceBuildPlan plan,
            WorkspaceMember member,
            WorkspaceBuildResult.MemberBuildResult memberBuild) {
        var build = member.config().build();
        Path testOutput = member.directory().resolve(build.testOutput()).toAbsolutePath().normalize();
        int testSourceCount = plan.executionContext()
                .fileSnapshot()
                .javaSources(member.directory(), build.testSources())
                .fileCount();
        return new WorkspaceTestCompileResult.MemberTestCompileResult(
                member.path(),
                new TestCompileResult(
                        memberBuild.result(),
                        testSourceCount,
                        0,
                        testOutput,
                        "",
                        true));
    }

    private Callable<WorkspaceTestCompileResult.MemberTestCompileResult> compileTestMember(
            Workspace workspace,
            WorkspaceMember member,
            WorkspaceBuildResult.MemberBuildResult memberBuild) {
        return () -> {
            TestRunService testRunService = testRunServices.forMember(workspace, member);
            return new WorkspaceTestCompileResult.MemberTestCompileResult(
                    member.path(),
                    testRunService.compileTests(
                            member.directory(),
                            member.config(),
                            testInputs(memberBuild)));
        };
    }

    private static WorkspaceTestCompileResult.MemberTestCompileResult getTestCompileResult(
            Future<WorkspaceTestCompileResult.MemberTestCompileResult> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BuildException(
                    "Workspace test compilation was interrupted while waiting for a member.",
                    exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new BuildException("Workspace test compilation failed.", cause);
        }
    }

    private static BuildResultWithClasspaths testInputs(
            WorkspaceBuildResult.MemberBuildResult memberBuild) {
        return new BuildResultWithClasspaths(
                memberBuild.result(),
                memberBuild.classpaths(),
                memberBuild.classpathPackages());
    }

    private static Map<String, WorkspaceMember> membersByPath(Workspace workspace) {
        Map<String, WorkspaceMember> members = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            members.put(member.path(), member);
        }
        return members;
    }

    private static Map<String, WorkspaceBuildResult.MemberBuildResult> buildsByPath(
            WorkspaceBuildResult result) {
        Map<String, WorkspaceBuildResult.MemberBuildResult> builds = new LinkedHashMap<>();
        for (WorkspaceBuildResult.MemberBuildResult member : result.members()) {
            builds.put(member.member(), member);
        }
        return builds;
    }

    private static int workspaceTestConcurrency(int memberCount) {
        if (memberCount <= 1) {
            return 1;
        }
        return Math.max(1, Math.min(memberCount, Runtime.getRuntime().availableProcessors()));
    }
}
