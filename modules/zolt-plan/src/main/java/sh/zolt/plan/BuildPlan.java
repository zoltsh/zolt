package sh.zolt.plan;

import sh.zolt.lockfile.ProjectLockfile;
import java.nio.file.Path;
import java.util.List;

public record BuildPlan(
        int schemaVersion,
        Path projectRoot,
        Path lockfilePath,
        String projectName,
        PlanTarget target,
        List<PlanNode> nodes) {
    public BuildPlan {
        schemaVersion = schemaVersion <= 0 ? 1 : schemaVersion;
        projectRoot = projectRoot.toAbsolutePath().normalize();
        lockfilePath = lockfilePath == null
                ? ProjectLockfile.in(projectRoot)
                : lockfilePath.toAbsolutePath().normalize();
        projectName = projectName == null ? "" : projectName;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /** True when the authoritative lockfile lives above the planned project directory. */
    public boolean workspaceLockfile() {
        return !lockfilePath.equals(ProjectLockfile.in(projectRoot));
    }

    public boolean blocked() {
        return nodes.stream().anyMatch(node -> node.status() == PlanNodeStatus.BLOCKED);
    }
}
