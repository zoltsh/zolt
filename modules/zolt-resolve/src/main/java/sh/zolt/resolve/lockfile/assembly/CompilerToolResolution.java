package sh.zolt.resolve.lockfile.assembly;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.version.VersionSelectionResult;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** One compiler toolchain resolved independently from the project's dependency graph. */
public record CompilerToolResolution(
        DependencyScope scope,
        String compilerName,
        ResolutionGraph graph,
        VersionSelectionResult selection,
        List<DependencyRequest> directRequests) {
    public CompilerToolResolution {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(selection, "selection");
        if (!scope.lockfileName().startsWith("tool-") || scope == DependencyScope.TOOL_EXEC) {
            throw new IllegalArgumentException("Compiler tool resolution requires a non-exec tool scope.");
        }
        compilerName = Objects.requireNonNull(compilerName, "compilerName").strip();
        if (compilerName.isEmpty()) {
            throw new IllegalArgumentException("Compiler tool name is required.");
        }
        directRequests = directRequests == null ? List.of() : List.copyOf(directRequests);
        if (directRequests.stream().anyMatch(request -> request.scope() != scope)) {
            throw new IllegalArgumentException(
                    "Compiler tool direct requests must use scope " + scope.lockfileName() + ".");
        }
    }

    /** Stable ordering used by every consumer of independently resolved compiler closures. */
    public static List<CompilerToolResolution> ordered(List<CompilerToolResolution> resolutions) {
        if (resolutions == null || resolutions.isEmpty()) {
            return List.of();
        }
        List<CompilerToolResolution> ordered = resolutions.stream()
                .sorted(Comparator.comparing((CompilerToolResolution resolution) ->
                                resolution.scope().lockfileName())
                        .thenComparing(CompilerToolResolution::compilerName))
                .toList();
        for (int index = 1; index < ordered.size(); index++) {
            if (ordered.get(index - 1).scope() == ordered.get(index).scope()) {
                throw new IllegalArgumentException(
                        "Compiler tool scope " + ordered.get(index).scope().lockfileName()
                                + " has more than one resolved closure.");
            }
        }
        return ordered;
    }
}
