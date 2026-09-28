package sh.zolt.resolve.lockfile.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.RequestOrigin;
import sh.zolt.resolve.version.VersionSelectionResult;

final class CompilerToolResolutionTest {
    @Test
    void retainsACompilerClosureInStableOrder() {
        CompilerToolResolution later = resolution(DependencyScope.TOOL_GROOVY, "Zulu");

        assertEquals(List.of(later), CompilerToolResolution.ordered(List.of(later)));
    }

    @Test
    void rejectsDuplicateCompilerScopes() {
        CompilerToolResolution first = resolution(DependencyScope.TOOL_GROOVY, "Alpha");
        CompilerToolResolution second = resolution(DependencyScope.TOOL_GROOVY, "Zulu");

        assertThrows(
                IllegalArgumentException.class,
                () -> CompilerToolResolution.ordered(List.of(second, first)));
    }

    @Test
    void rejectsExecToolScopes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> resolution(DependencyScope.TOOL_EXEC, "exec"));
    }

    @Test
    void rejectsDirectRequestsFromAnotherScope() {
        DependencyRequest compileRequest = new DependencyRequest(
                new PackageId("org.apache.groovy", "groovy"),
                "4.0.23",
                DependencyScope.COMPILE,
                RequestOrigin.DIRECT);

        assertThrows(
                IllegalArgumentException.class,
                () -> new CompilerToolResolution(
                        DependencyScope.TOOL_GROOVY,
                        "Groovy",
                        new ResolutionGraph(List.of(), List.of(), List.of()),
                        new VersionSelectionResult(List.of(), List.of()),
                        List.of(compileRequest)));
    }

    private static CompilerToolResolution resolution(DependencyScope scope, String name) {
        return new CompilerToolResolution(
                scope,
                name,
                new ResolutionGraph(List.of(), List.of(), List.of()),
                new VersionSelectionResult(List.of(), List.of()),
                List.of());
    }
}
