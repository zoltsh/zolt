package sh.zolt.workspace.packaging;

import sh.zolt.build.packaging.PackageResult;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceRunFiles;
import java.nio.file.Path;
import java.util.List;

/**
 * Launch data backed by immutable copies of packaged workspace artifacts and class directories.
 */
public record WorkspaceRunPackageSnapshot(
        WorkspacePackageResult packageResult,
        WorkspaceRunFiles files,
        List<MemberLaunch> members) implements AutoCloseable {
    public WorkspaceRunPackageSnapshot {
        members = List.copyOf(members);
    }

    @Override
    public void close() {
        files.close();
    }

    public record MemberLaunch(
            String member,
            WorkspaceBuildResult.MemberBuildResult build,
            PackageResult originalPackage,
            PackageResult snapshotPackage,
            List<Path> runtimeEntries,
            Path java,
            String mainClass) {
        public MemberLaunch {
            runtimeEntries = List.copyOf(runtimeEntries);
        }
    }
}
