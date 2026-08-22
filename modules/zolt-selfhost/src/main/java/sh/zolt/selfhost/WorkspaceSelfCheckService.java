package sh.zolt.selfhost;

import sh.zolt.build.nativeimage.NativeBuildResult;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.run.RunPackageResult;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceBuildService;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceMemberSelector;
import sh.zolt.workspace.packaging.WorkspaceNativeBuildResult;
import sh.zolt.workspace.packaging.WorkspaceNativeBuildService;
import sh.zolt.workspace.packaging.WorkspacePackageResult;
import sh.zolt.workspace.packaging.WorkspacePackageService;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import sh.zolt.workspace.packaging.WorkspaceRunPackageResult;
import sh.zolt.workspace.packaging.WorkspaceRunPackageService;
import sh.zolt.workspace.service.WorkspaceSelection;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.test.WorkspaceTestResult;
import sh.zolt.workspace.test.WorkspaceTestService;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class WorkspaceSelfCheckService {
    private final SelfCheckService.NativeBinaryRunner nativeBinaryRunner;

    WorkspaceSelfCheckService(SelfCheckService.NativeBinaryRunner nativeBinaryRunner) {
        this.nativeBinaryRunner = nativeBinaryRunner;
    }

    SelfCheckResult check(
            Path root,
            Path cacheRoot,
            boolean offline,
            boolean nativeCheck,
            Path nativeImageExecutable,
            List<SelfCheckResult.SelfCheckStep> steps) {
        WorkspaceSelectionRequest appSelection = WorkspaceSelectionRequest.defaults();
        ProjectConfig config;
        try {
            config = selectedAppConfig(root);
        } catch (RuntimeException exception) {
            steps.add(failed("workspace app config", exception.getMessage()));
            return new SelfCheckResult(steps);
        }
        try {
            ResolveResult resolveResult = new WorkspaceResolveService().resolve(root, cacheRoot, true, offline);
            steps.add(new SelfCheckResult.SelfCheckStep(
                    "resolve --workspace --locked",
                    true,
                    "verified " + resolveResult.lockfilePath()));
        } catch (RuntimeException exception) {
            steps.add(failed("resolve --workspace --locked", exception.getMessage()));
            return new SelfCheckResult(steps);
        }

        try {
            WorkspaceBuildResult buildResult = new WorkspaceBuildService().build(root, cacheRoot, offline, appSelection);
            steps.add(new SelfCheckResult.SelfCheckStep(
                    "build --workspace",
                    true,
                    "compiled " + buildResult.sourceCount() + " main source files"));
        } catch (RuntimeException exception) {
            steps.add(failed("build --workspace", exception.getMessage()));
            return new SelfCheckResult(steps);
        }

        try {
            WorkspaceTestResult testResult = new WorkspaceTestService().test(root, cacheRoot, appSelection);
            steps.add(new SelfCheckResult.SelfCheckStep(
                    "test --workspace",
                    true,
                    "ran tests with " + testResult.testSourceCount() + " test source files"));
        } catch (RuntimeException exception) {
            steps.add(failed("test --workspace", exception.getMessage()));
            return new SelfCheckResult(steps);
        }

        try {
            WorkspacePackageResult packageResult = new WorkspacePackageService().packageJars(root, cacheRoot, appSelection);
            steps.add(new SelfCheckResult.SelfCheckStep(
                    "package --workspace",
                    true,
                    "wrote " + firstPackage(packageResult).jarPath()));
        } catch (RuntimeException exception) {
            steps.add(failed("package --workspace", exception.getMessage()));
            return new SelfCheckResult(steps);
        }

        try {
            WorkspaceRunPackageResult runPackageResult = new WorkspaceRunPackageService()
                    .runPackages(root, cacheRoot, appSelection, List.of("--version"));
            String expectedVersion = config.project().version();
            String output = firstRunPackage(runPackageResult).javaRunResult().output();
            if (!output.trim().equals(expectedVersion)) {
                steps.add(failed(
                        "run packaged jar",
                        "expected packaged application to print only `" + expectedVersion + "` for --version"));
                return new SelfCheckResult(steps);
            }
            steps.add(new SelfCheckResult.SelfCheckStep(
                    "run packaged jar",
                    true,
                    "printed " + expectedVersion));
        } catch (RuntimeException exception) {
            steps.add(failed("run packaged jar", exception.getMessage()));
            return new SelfCheckResult(steps);
        }

        return nativeCheck
                ? checkNative(root, cacheRoot, appSelection, nativeImageExecutable, config, steps)
                : new SelfCheckResult(steps);
    }

    static boolean usesRealWorkspace(Path root) {
        Optional<Workspace> workspace = new ManifestWorkspaceLoader().discover(root);
        return workspace.stream()
                .flatMap(value -> value.members().stream())
                .anyMatch(member -> !member.path().equals("."));
    }

    static ProjectConfig selectedAppConfig(Path root) {
        Workspace workspace = new ManifestWorkspaceLoader()
                .discover(root)
                .orElseThrow(() -> new IllegalStateException("No workspace config found for self-check."));
        WorkspaceSelection selection = new WorkspaceMemberSelector().select(workspace, WorkspaceSelectionRequest.defaults());
        String selectedMember = selection.selectedMembers().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("workspace self-check selected no application member."));
        return workspace.members().stream()
                .filter(member -> member.path().equals(selectedMember))
                .findFirst()
                .map(WorkspaceMember::config)
                .orElseThrow(() -> new IllegalStateException(
                        "workspace self-check selected unknown application member `" + selectedMember + "`."));
    }

    private SelfCheckResult checkNative(
            Path root,
            Path cacheRoot,
            WorkspaceSelectionRequest appSelection,
            Path nativeImageExecutable,
            ProjectConfig config,
            List<SelfCheckResult.SelfCheckStep> steps) {
        NativeBuildResult nativeBuildResult;
        try {
            WorkspaceNativeBuildResult workspaceNativeBuildResult = new WorkspaceNativeBuildService()
                    .buildNative(root, cacheRoot, appSelection, nativeImageExecutable);
            nativeBuildResult = firstNativeBuild(workspaceNativeBuildResult);
            steps.add(new SelfCheckResult.SelfCheckStep(
                    "native --workspace",
                    true,
                    "built " + nativeBuildResult.nativeImageResult().outputBinary()));
        } catch (RuntimeException exception) {
            steps.add(failed("native --workspace", exception.getMessage()));
            return new SelfCheckResult(steps);
        }

        try {
            SelfCheckService.NativeBinaryRunResult nativeRunResult = nativeBinaryRunner.run(
                    nativeBuildResult.nativeImageResult().outputBinary(),
                    List.of("--version"));
            String expectedVersion = config.project().version();
            if (!nativeRunResult.output().trim().equals(expectedVersion)) {
                steps.add(failed(
                        "run native binary",
                        "expected native binary to print only `" + expectedVersion + "` for --version"));
                return new SelfCheckResult(steps);
            }
            steps.add(new SelfCheckResult.SelfCheckStep(
                    "run native binary",
                    true,
                    "printed " + expectedVersion));
        } catch (RuntimeException exception) {
            steps.add(failed("run native binary", exception.getMessage()));
        }
        return new SelfCheckResult(steps);
    }

    private static PackageResult firstPackage(WorkspacePackageResult result) {
        return result.members().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("workspace package produced no selected members"))
                .result();
    }

    private static RunPackageResult firstRunPackage(WorkspaceRunPackageResult result) {
        return result.members().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("workspace run-package produced no selected members"))
                .result();
    }

    private static NativeBuildResult firstNativeBuild(WorkspaceNativeBuildResult result) {
        return result.members().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("workspace native produced no selected members"))
                .result();
    }

    private static SelfCheckResult.SelfCheckStep failed(String name, String message) {
        return new SelfCheckResult.SelfCheckStep(name, false, message == null ? "check failed" : message);
    }
}
