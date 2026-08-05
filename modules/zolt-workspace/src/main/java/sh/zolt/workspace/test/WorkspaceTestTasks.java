package sh.zolt.workspace.test;

import sh.zolt.build.BuildResultWithClasspaths;
import sh.zolt.build.profile.TestProfileSettings;
import sh.zolt.build.testruntime.TestReportSettings;
import sh.zolt.build.testruntime.TestRunService;
import sh.zolt.project.ProjectConfig;
import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.runtime.TestRunException;
import sh.zolt.test.shard.TestShardSpec;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceMember;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

final class WorkspaceTestTasks {
    private WorkspaceTestTasks() {
    }

    static List<Callable<WorkspaceTestResult.MemberTestRunResult>> unit(
            Workspace workspace,
            List<String> memberPaths,
            Map<String, WorkspaceMember> membersByPath,
            Map<String, WorkspaceBuildResult.MemberBuildResult> buildsByPath,
            WorkspaceTestRunServiceResolver testRunServices,
            List<TestRunService> usedServices,
            TestSelection testSelection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents,
            String suiteName,
            TestShardSpec shard,
            TestProfileSettings profileSettings) {
        return memberPaths.stream()
                .map(memberPath -> (Callable<WorkspaceTestResult.MemberTestRunResult>) () -> {
                    WorkspaceMember member = membersByPath.get(memberPath);
                    WorkspaceBuildResult.MemberBuildResult memberBuild =
                            buildsByPath.get(memberPath);
                    TestRunService testRunService =
                            testRunServices.forMember(workspace, member);
                    remember(usedServices, testRunService);
                    try {
                        return new WorkspaceTestResult.MemberTestRunResult(
                                member.path(),
                                testRunService.runCompiledTests(
                                        member.directory(),
                                        member.config(),
                                        memberBuild.classpaths(),
                                        testRunService.compileTests(
                                                member.directory(),
                                                member.config(),
                                                testInputs(memberBuild)),
                                        testSelection,
                                        jvmArguments,
                                        reportSettings.forWorkspaceMember(
                                                member.path()),
                                        cliEvents,
                                        suiteName,
                                        shard,
                                        profileSettings.forWorkspaceMember(
                                                member.path())));
                    } catch (RuntimeException failure) {
                        throw memberFailure(
                                member.path(),
                                failure);
                    }
                })
                .toList();
    }

    static List<Callable<WorkspaceTestResult.MemberTestRunResult>> integration(
            Workspace workspace,
            List<String> memberPaths,
            Map<String, WorkspaceMember> membersByPath,
            Map<String, WorkspaceBuildResult.MemberBuildResult> buildsByPath,
            WorkspaceTestRunServiceResolver testRunServices,
            List<TestRunService> usedServices,
            TestSelection testSelection,
            TestJvmArguments jvmArguments,
            TestReportSettings reportSettings,
            List<String> cliEvents) {
        return memberPaths.stream()
                .map(memberPath -> (Callable<WorkspaceTestResult.MemberTestRunResult>) () -> {
                    WorkspaceMember member = membersByPath.get(memberPath);
                    WorkspaceBuildResult.MemberBuildResult memberBuild =
                            buildsByPath.get(memberPath);
                    ProjectConfig integrationConfig =
                            member.config().withBuildSettings(
                                    member.config().build().asIntegrationTestBuild());
                    TestRunService testRunService =
                            testRunServices.forMember(workspace, member);
                    remember(usedServices, testRunService);
                    try {
                        return new WorkspaceTestResult.MemberTestRunResult(
                                member.path(),
                                testRunService.runTests(
                                        member.directory(),
                                        integrationConfig,
                                        testInputs(memberBuild),
                                        testSelection,
                                        jvmArguments,
                                        reportSettings.forWorkspaceMember(
                                                member.path()),
                                        cliEvents,
                                        "all",
                                        null));
                    } catch (RuntimeException failure) {
                        throw memberFailure(
                                member.path(),
                                failure);
                    }
                })
                .toList();
    }

    private static TestRunException memberFailure(
            String memberPath,
            RuntimeException failure) {
        return new TestRunException(
                "Workspace member `"
                        + memberPath
                        + "` tests failed.\n"
                        + failure.getMessage(),
                failure);
    }

    private static void remember(
            List<TestRunService> usedServices,
            TestRunService service) {
        synchronized (usedServices) {
            usedServices.add(service);
        }
    }

    private static BuildResultWithClasspaths testInputs(
            WorkspaceBuildResult.MemberBuildResult memberBuild) {
        return new BuildResultWithClasspaths(
                memberBuild.result(),
                memberBuild.classpaths(),
                memberBuild.classpathPackages());
    }
}
