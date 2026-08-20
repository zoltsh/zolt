package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.toml.schema.ManifestPath;

final class ManifestDiagnosticPathTest {
    @Test
    void preservesStructuralSegmentsAndRendersIndexesOnTheirOwningSegment() {
        ManifestPath dynamic = ManifestPath.of("platforms", "org.example:demo");
        ManifestDiagnosticPath plain = ManifestDiagnosticPath.of(dynamic);
        ManifestDiagnosticPath indexed = ManifestDiagnosticPath
                .indexed(ManifestPath.of("dependencies", "policy", "deny"), 1)
                .child("coordinate");

        assertSame(dynamic, plain.structure());
        assertEquals(List.of("platforms", "org.example:demo"), plain.structure().segments());
        assertEquals(
                List.of("dependencies", "policy", "deny[1]", "coordinate"),
                indexed.structure().segments());
        assertEquals("dependencies.policy.deny[1].coordinate", indexed.toString());
        assertThrows(
                UnsupportedOperationException.class,
                () -> indexed.structure().segments().add("forbidden"));
    }

    @Test
    void rejectsNegativeIndexesAndInvalidChildSegments() {
        ManifestDiagnosticPath path = ManifestDiagnosticPath.of(ManifestPath.of("dependencies"));

        assertThrows(IllegalArgumentException.class, () -> path.indexed(-1));
        assertThrows(IllegalArgumentException.class, () -> path.child(""));
    }
}
