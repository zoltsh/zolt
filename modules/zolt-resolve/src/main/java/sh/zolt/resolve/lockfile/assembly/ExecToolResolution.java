package sh.zolt.resolve.lockfile.assembly;

import sh.zolt.lockfile.LockConflict;
import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.version.VersionSelectionResult;
import java.util.List;

/**
 * One named exec tool resolved as its own isolated unit. {@code graph}/{@code selection} come from a
 * per-tool resolution (its coordinates as the only roots), so the tool's version line never collides
 * with another tool's or with the project's compile/runtime graph. The assembler tags every locked jar
 * from this resolution with {@code toolGroups = [toolName]} and merges jars shared with other tools by
 * unioning their groups; jars at a different version stay distinct entries.
 */
public record ExecToolResolution(
        String toolName,
        ResolutionGraph graph,
        VersionSelectionResult selection,
        List<DependencyRequest> directRequests) {
    public ExecToolResolution {
        if (LockConflict.isReservedCompilerToolGroup(toolName)) {
            throw new IllegalArgumentException(
                    "Exec tool names must not use the reserved `compiler:` conflict namespace.");
        }
        directRequests = directRequests == null ? List.of() : List.copyOf(directRequests);
    }
}
