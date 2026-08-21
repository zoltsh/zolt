package sh.zolt.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DependencyLaneTest {
    @Test
    void exposesEveryAuthoredDependencyLane() {
        assertEquals(
                EnumSet.of(
                        DependencyLane.API,
                        DependencyLane.IMPLEMENTATION,
                        DependencyLane.RUNTIME,
                        DependencyLane.PROVIDED,
                        DependencyLane.DEV,
                        DependencyLane.TEST,
                        DependencyLane.PROCESSOR,
                        DependencyLane.TEST_PROCESSOR),
                EnumSet.allOf(DependencyLane.class));
    }

    @Test
    void keepsApiAndImplementationDistinct() {
        assertNotEquals(DependencyLane.API, DependencyLane.IMPLEMENTATION);
    }

    @Test
    void exposesFrozenCanonicalOrderWithoutRelyingOnOrdinals() {
        assertEquals(
                List.of(
                        DependencyLane.IMPLEMENTATION,
                        DependencyLane.API,
                        DependencyLane.RUNTIME,
                        DependencyLane.PROVIDED,
                        DependencyLane.DEV,
                        DependencyLane.TEST,
                        DependencyLane.PROCESSOR,
                        DependencyLane.TEST_PROCESSOR),
                Arrays.stream(DependencyLane.values())
                        .sorted(Comparator.comparingInt(DependencyLane::canonicalOrder))
                        .toList());
    }
}
