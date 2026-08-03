package sh.zolt.workspace.run;

import sh.zolt.classpath.Classpath;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceRunFiles;
import java.nio.file.Path;
import java.util.List;

/**
 * Launch data backed by immutable copies of workspace class directories.
 */
public record WorkspaceRunSnapshot(
        WorkspaceBuildResult buildResult,
        WorkspaceRunFiles files,
        List<MemberLaunch> members) implements AutoCloseable {
    public WorkspaceRunSnapshot {
        members = List.copyOf(members);
    }

    @Override
    public void close() {
        files.close();
    }

    public record MemberLaunch(
            String member,
            WorkspaceBuildResult.MemberBuildResult build,
            Path java,
            Classpath classpath,
            String mainClass) {
    }
}
