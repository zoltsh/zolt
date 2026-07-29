package sh.zolt.workspace.run;

import sh.zolt.build.RunException;
import sh.zolt.build.run.JavaRunResult;
import sh.zolt.build.run.JavaRunner;
import sh.zolt.build.run.RunResult;
import sh.zolt.classpath.Classpath;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceBuildRequirements;
import sh.zolt.workspace.service.WorkspaceBuildService;
import sh.zolt.workspace.service.WorkspaceJdkCheckerResolver;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceSelection;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class WorkspaceRunService {
    private final WorkspaceBuildService workspaceBuildService;
    private final WorkspaceJdkCheckerResolver jdkCheckers;
    private final JavaRunner javaRunner;

    public WorkspaceRunService() {
        this(new JdkDetector());
    }

    public WorkspaceRunService(ResolveService resolveService) {
        this(new JdkDetector(), resolveService);
    }

    WorkspaceRunService(JdkChecker jdkDetector) {
        this(jdkDetector, new ResolveService());
    }

    WorkspaceRunService(JdkChecker jdkDetector, ResolveService resolveService) {
        this(
                new WorkspaceBuildService(jdkDetector, resolveService),
                jdkDetector,
                new JavaRunner());
    }

    WorkspaceRunService(
            WorkspaceBuildService workspaceBuildService,
            JdkChecker jdkDetector,
            JavaRunner javaRunner) {
        this.workspaceBuildService = workspaceBuildService;
        this.jdkCheckers = WorkspaceJdkCheckerResolver.fixed(jdkDetector);
        this.javaRunner = javaRunner;
    }

    private WorkspaceRunService(
            WorkspaceBuildService workspaceBuildService,
            WorkspaceJdkCheckerResolver jdkCheckers,
            JavaRunner javaRunner) {
        this.workspaceBuildService = workspaceBuildService;
        this.jdkCheckers = jdkCheckers;
        this.javaRunner = javaRunner;
    }

    public WorkspaceRunService withJdkCheckers(WorkspaceJdkCheckerResolver jdkCheckers) {
        return new WorkspaceRunService(
                workspaceBuildService.withJdkCheckers(jdkCheckers),
                jdkCheckers,
                javaRunner);
    }

    public WorkspaceRunResult run(
            Path startDirectory,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest,
            List<String> arguments,
            Consumer<String> outputConsumer) {
        WorkspaceBuildPlan plan = planRun(startDirectory, cacheRoot, selectionRequest);
        WorkspaceBuildResult buildResult = buildRunInputs(plan, cacheRoot);
        return runBuiltMembers(plan, buildResult, arguments, outputConsumer);
    }

    public WorkspaceBuildPlan planRun(
            Path startDirectory,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest) {
        return workspaceBuildService.planBuild(startDirectory, cacheRoot, false, selectionRequest);
    }

    public WorkspaceBuildResult buildRunInputs(WorkspaceBuildPlan plan, Path cacheRoot) {
        return workspaceBuildService.build(
                plan,
                cacheRoot,
                WorkspaceBuildRequirements.runtime());
    }

    public WorkspaceRunResult runBuiltMembers(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult,
            List<String> arguments,
            Consumer<String> outputConsumer) {
        Workspace workspace = plan.workspace();
        WorkspaceSelection selection = plan.selection();
        Map<String, WorkspaceMember> membersByPath = membersByPath(workspace);
        Map<String, WorkspaceBuildResult.MemberBuildResult> buildsByPath = buildsByPath(buildResult);

        List<WorkspaceRunResult.MemberRunResult> results = new ArrayList<>();
        for (String memberPath : selection.selectedMembers()) {
            WorkspaceMember member = membersByPath.get(memberPath);
            WorkspaceBuildResult.MemberBuildResult memberBuild = buildsByPath.get(memberPath);
            String mainClass = member.config().project().main().orElseThrow(() -> new RunException(
                    "Workspace member `"
                            + member.path()
                            + "` has no main class configured. Add [project].main to its zolt.toml or choose an application member."));
            JdkStatus jdkStatus = jdkCheckers.forMember(workspace, member).detect(member.config().project().java());
            if (!jdkStatus.ok()) {
                throw new RunException("JDK check failed. " + String.join(" ", jdkStatus.problems()));
            }
            List<Path> runtimeEntries = new ArrayList<>();
            runtimeEntries.add(memberBuild.result().outputDirectory());
            runtimeEntries.addAll(memberBuild.classpaths().runtime().entries());
            JavaRunResult javaRunResult = javaRunner.run(
                    jdkStatus.java().orElseThrow(),
                    new Classpath(runtimeEntries),
                    mainClass,
                    arguments,
                    outputConsumer);
            results.add(new WorkspaceRunResult.MemberRunResult(
                    member.path(),
                    new RunResult(memberBuild.result(), javaRunResult)));
        }
        return new WorkspaceRunResult(buildResult.resolveResult(), buildResult.members(), results);
    }

    private static Map<String, WorkspaceMember> membersByPath(Workspace workspace) {
        Map<String, WorkspaceMember> members = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            members.put(member.path(), member);
        }
        return members;
    }

    private static Map<String, WorkspaceBuildResult.MemberBuildResult> buildsByPath(WorkspaceBuildResult result) {
        Map<String, WorkspaceBuildResult.MemberBuildResult> builds = new LinkedHashMap<>();
        for (WorkspaceBuildResult.MemberBuildResult member : result.members()) {
            builds.put(member.member(), member);
        }
        return builds;
    }
}
