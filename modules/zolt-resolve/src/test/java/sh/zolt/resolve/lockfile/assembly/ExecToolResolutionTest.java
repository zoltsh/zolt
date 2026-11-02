package sh.zolt.resolve.lockfile.assembly;

import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.version.VersionSelectionResult;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ExecToolResolutionTest {
    @Test
    void rejectsTheReservedCompilerConflictNamespace() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecToolResolution(
                        "compiler:tool-kotlin:opaque",
                        new ResolutionGraph(List.of(), List.of(), List.of()),
                        new VersionSelectionResult(List.of(), List.of()),
                        List.of()));
    }
}
