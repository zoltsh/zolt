package sh.zolt.resolve.lockfile.assembly;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.version.VersionSelectionResult;
import java.util.List;

/**
 * The Groovy compiler toolchain resolved independently from the project's compile/runtime graph. The
 * separate selection preserves the application and compiler versions as scope-qualified rows so build
 * validation can reject compiler/runtime skew without tool mediation rewriting application dependencies.
 */
public record GroovyToolResolution(
        ResolutionGraph graph,
        VersionSelectionResult selection,
        List<DependencyRequest> directRequests) {
    public GroovyToolResolution {
        directRequests = directRequests == null ? List.of() : List.copyOf(directRequests);
    }

    /** Adapts the original public Groovy API to the generic compiler-tool representation. */
    public CompilerToolResolution asCompilerToolResolution() {
        return new CompilerToolResolution(
                DependencyScope.TOOL_GROOVY,
                "Groovy",
                graph,
                selection,
                directRequests);
    }
}
