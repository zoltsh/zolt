package sh.zolt.workspace.service;

import sh.zolt.build.run.RunFiles;
import java.nio.file.Path;
import java.util.List;

/** Workspace facade for the run snapshots shared with standalone applications. */
public final class WorkspaceRunFiles implements AutoCloseable {
    private final RunFiles files;

    private WorkspaceRunFiles(RunFiles files) {
        this.files = files;
    }

    public static WorkspaceRunFiles create(Path workspaceRoot) {
        return new WorkspaceRunFiles(RunFiles.create(workspaceRoot));
    }

    public Path root() {
        return files.root();
    }

    public Path capture(Path source) {
        return files.capture(source);
    }

    public List<Path> remap(List<Path> entries) {
        return files.remap(entries);
    }

    public Path remap(Path entry) {
        return files.remap(entry);
    }

    @Override
    public void close() {
        files.close();
    }
}
