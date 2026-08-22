package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class DependencyCoordinateTest {
    @Test
    void preservesTheExactPortableGroupAndArtifactSpelling() {
        DependencyCoordinate coordinate = new DependencyCoordinate("Com.Example_2:Native-Client");

        assertEquals("Com.Example_2", coordinate.group());
        assertEquals("Native-Client", coordinate.artifact());
        assertEquals("Com.Example_2:Native-Client", coordinate.value());
        assertEquals("Com.Example_2:Native-Client", coordinate.toString());
    }

    @Test
    void rejectsAnythingOtherThanAnExactTwoSegmentCoordinate() {
        for (String value : List.of(
                "",
                "group",
                ":artifact",
                "group:",
                "group:artifact:1.0",
                "group :artifact",
                "group:arti fact",
                "group:${artifact}",
                "group/artifact:name",
                "gr\u00f6up:artifact",
                "group:artifact\n")) {
            assertThrows(IllegalArgumentException.class, () -> new DependencyCoordinate(value), value);
        }
        assertThrows(NullPointerException.class, () -> new DependencyCoordinate(null));
    }
}
