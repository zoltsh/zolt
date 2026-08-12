package sh.zolt.cli.command.dependency;

import sh.zolt.resolve.ResolveResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Byte-level effects shared by project and workspace-root manifest transactions. */
record ManifestCommitResult(
        ResolveResult resolveResult,
        Path manifestPath,
        Path lockfilePath,
        boolean manifestChanged,
        boolean lockfileChanged) {

    static ManifestCommitResult from(ManifestEditResult result) {
        return new ManifestCommitResult(
                result.resolveResult(),
                result.manifestPath(),
                result.lockfilePath(),
                result.manifestChanged(),
                result.lockfileChanged());
    }

    List<Path> changedPaths() {
        List<Path> paths = new ArrayList<>();
        if (manifestChanged) {
            paths.add(manifestPath);
        }
        if (lockfileChanged) {
            paths.add(lockfilePath);
        }
        return List.copyOf(paths);
    }
}
