package sh.zolt.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.EnumSet;
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
}
