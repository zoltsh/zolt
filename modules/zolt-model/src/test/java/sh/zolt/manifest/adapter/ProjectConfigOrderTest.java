package sh.zolt.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Adapter projections are iteration-order carriers.
 *
 * <p>{@code Map.copyOf}/{@code Set.copyOf} randomize iteration order per JVM, so a projection copied
 * that way renders different bytes on every run. Package evidence hashes these projections, which is
 * how a salted copy makes {@code zolt check} disagree with the {@code zolt package} run that wrote
 * the evidence — and with itself on the next run.
 */
final class ProjectConfigOrderTest {
    /** Deliberately not sorted, and large enough that a salted order cannot match by accident. */
    private static final List<String> KEYS = List.of(
            "zeta", "yankee", "xray", "whiskey", "victor", "uniform", "tango", "sierra");

    @Test
    void mapPublishesSourceInsertionOrder() {
        LinkedHashMap<String, String> source = new LinkedHashMap<>();
        KEYS.forEach(key -> source.put(key, key + "-value"));

        Map<String, String> published = ProjectConfigOrder.map(source);

        assertEquals(KEYS, List.copyOf(published.keySet()));
    }

    @Test
    void setPublishesSourceInsertionOrder() {
        Set<String> published = ProjectConfigOrder.set(new LinkedHashSet<>(KEYS));

        assertEquals(KEYS, List.copyOf(published));
    }

    @Test
    void renderingIsStableForTheSameSource() {
        LinkedHashMap<String, String> source = new LinkedHashMap<>();
        KEYS.forEach(key -> source.put(key, key + "-value"));

        assertEquals(
                ProjectConfigOrder.map(source).toString(),
                ProjectConfigOrder.map(new LinkedHashMap<>(source)).toString());
    }

    @Test
    void emptyAndNullSourcesPublishEmptyCollections() {
        assertEquals(Map.of(), ProjectConfigOrder.map(null));
        assertEquals(Map.of(), ProjectConfigOrder.map(Map.of()));
        assertEquals(Set.of(), ProjectConfigOrder.set(null));
        assertEquals(Set.of(), ProjectConfigOrder.set(Set.of()));
    }

    @Test
    void publishedCollectionsAreUnmodifiable() {
        Map<String, String> map = ProjectConfigOrder.map(new LinkedHashMap<>(Map.of("a", "b")));
        Set<String> set = ProjectConfigOrder.set(new LinkedHashSet<>(List.of("a")));

        assertThrows(UnsupportedOperationException.class, () -> map.put("c", "d"));
        assertThrows(UnsupportedOperationException.class, () -> set.add("c"));
    }
}
