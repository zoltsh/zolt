package sh.zolt.toml.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ManifestPathTest {
    @Test
    void copiesSegmentsAndCreatesChildPaths() {
        ArrayList<String> source = new ArrayList<>(List.of("dependencies"));
        ManifestPath path = new ManifestPath(source);
        source.add("runtime");

        assertEquals(List.of("dependencies"), path.segments());
        assertEquals(ManifestPath.of("dependencies", "api"), path.child("api"));
        assertEquals("dependencies.api", path.child("api").toString());
        assertEquals(List.of(), path.placeholderNames());
        assertEquals(
                List.of("id", "coordinate"),
                ManifestPath.of("generated", "<id>", "<coordinate>").placeholderNames());
        assertThrows(UnsupportedOperationException.class, () -> path.segments().add("test"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ManifestPath.of("generated", "<id>").placeholderNames().add("other"));
    }

    @Test
    void rejectsMissingOrMalformedSegments() {
        assertThrows(IllegalArgumentException.class, () -> new ManifestPath(List.of()));
        assertThrows(IllegalArgumentException.class, () -> ManifestPath.of(" "));
        assertThrows(IllegalArgumentException.class, () -> ManifestPath.of(" project"));
        assertThrows(IllegalArgumentException.class, () -> ManifestPath.of("project\n"));
    }

    @Test
    void comparesPathsBySegments() {
        assertEquals(-1, Integer.signum(ManifestPath.of("dependencies").compareTo(ManifestPath.of("project"))));
        assertEquals(
                -1,
                Integer.signum(ManifestPath.of("project").compareTo(ManifestPath.of("project", "name"))));
    }
}
