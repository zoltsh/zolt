package sh.zolt.plan;

import sh.zolt.lockfile.ProjectLockfile;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.project.ProjectConfig;

/**
 * One explicit plan request: which project directory to plan, and which lockfile is authoritative
 * for it.
 *
 * <p>A workspace has exactly one authoritative {@code zolt.lock} at its root (design §6.9), so a
 * member-directory command must plan against the workspace root's lockfile and never against a
 * member-local file. Deriving the path from {@code projectRoot} inside the planner made that
 * impossible to express, so the caller — which already knows whether the directory is a member —
 * supplies it here and every planner receives the same authoritative path.
 */
public record BuildPlanRequest(
        Path projectRoot,
        Path lockfilePath,
        ProjectConfig config,
        PlanTarget target,
        Optional<Path> reportsDir,
        Optional<Path> nativeImageExecutable,
        Optional<TestRuntimePlan> testRuntime) {

    public BuildPlanRequest {
        projectRoot = Objects.requireNonNull(projectRoot, "Plan project root is required.")
                .toAbsolutePath()
                .normalize();
        lockfilePath = Objects.requireNonNull(lockfilePath, "Plan lockfile path is required.")
                .toAbsolutePath()
                .normalize();
        config = Objects.requireNonNull(config, "Plan project configuration is required.");
        target = Objects.requireNonNull(target, "Plan target is required.");
        reportsDir = reportsDir == null ? Optional.empty() : reportsDir;
        nativeImageExecutable = nativeImageExecutable == null ? Optional.empty() : nativeImageExecutable;
        testRuntime = testRuntime == null ? Optional.empty() : testRuntime;
    }

    /** A standalone project: its own directory owns its lockfile. */
    public static BuildPlanRequest standalone(
            Path projectRoot, ProjectConfig config, PlanTarget target) {
        return new BuildPlanRequest(
                projectRoot,
                ProjectLockfile.in(projectRoot),
                config,
                target,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /**
     * The lockfile a project directory owns: the workspace root's when it is a member, its own
     * otherwise.
     */
    public static Path lockfileFor(Path projectRoot, Optional<Path> workspaceRoot) {
        return ProjectLockfile.in(workspaceRoot.orElse(projectRoot));
    }

    /** True when the authoritative lockfile lives above the planned project directory. */
    public boolean workspaceLockfile() {
        return !lockfilePath.equals(ProjectLockfile.in(projectRoot));
    }

    public BuildPlanRequest withReportsDir(Optional<Path> value) {
        return new BuildPlanRequest(
                projectRoot, lockfilePath, config, target, value, nativeImageExecutable, testRuntime);
    }

    public BuildPlanRequest withNativeImageExecutable(Optional<Path> value) {
        return new BuildPlanRequest(
                projectRoot, lockfilePath, config, target, reportsDir, value, testRuntime);
    }

    public BuildPlanRequest withTestRuntime(Optional<TestRuntimePlan> value) {
        return new BuildPlanRequest(
                projectRoot, lockfilePath, config, target, reportsDir, nativeImageExecutable, value);
    }
}
