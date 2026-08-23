package sh.zolt.ide;

import sh.zolt.project.ProjectConfig;
import java.nio.file.Path;
import java.util.List;

@FunctionalInterface
public interface IdeFrameworkModelProvider {
    /**
     * @param lockfilePath the lock that governs this project, decided at the command boundary. A
     *     framework model is a read of resolved dependencies, so it must never re-derive the path from
     *     {@code root} — for a workspace member that would name a file the workspace never creates.
     */
    IdeModel.FrameworkInfo build(
            Path root,
            Path lockfilePath,
            Path cacheRoot,
            ProjectConfig config,
            List<IdeModel.Diagnostic> diagnostics);

    static IdeFrameworkModelProvider none() {
        return (root, lockfilePath, cacheRoot, config, diagnostics) -> new IdeModel.FrameworkInfo(new IdeModel.QuarkusInfo(
                false,
                null,
                "disabled",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of()));
    }
}
