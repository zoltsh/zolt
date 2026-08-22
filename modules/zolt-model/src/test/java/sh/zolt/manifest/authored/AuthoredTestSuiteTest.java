package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.JavaBinaryClassName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.TestClassPattern;

final class AuthoredTestSuiteTest {
    @Test
    void retainsSelectionAndCanonicalizesExclusiveLocks() {
        ArrayList<LocalId> resources = new ArrayList<>(List.of(
                new LocalId("redis"), new LocalId("database")));
        ArrayList<AuthoredTestSuite.Lock> locks = new ArrayList<>(List.of(
                lock("com.example.ZetaTest", List.of(new LocalId("network"))),
                lock("com.example.AlphaTest", resources)));
        AuthoredTestSuite suite = new AuthoredTestSuite(
                List.of(new TestClassPattern("*SmokeTest")),
                List.of(new TestClassPattern("*FlakySmokeTest")),
                List.of("smoke"),
                List.of("slow"),
                Optional.of(4),
                locks);
        resources.clear();
        locks.clear();

        assertEquals(4, suite.workers().orElseThrow());
        assertEquals(
                List.of("com.example.AlphaTest", "com.example.ZetaTest"),
                suite.locks().stream().map(value -> value.className().value()).toList());
        assertEquals(
                List.of(new LocalId("database"), new LocalId("redis")),
                suite.locks().getFirst().resources());
        assertThrows(UnsupportedOperationException.class, () -> suite.locks().clear());
    }

    @Test
    void rejectsInvalidWorkerAndLockShapes() {
        assertThrows(IllegalArgumentException.class, () -> suite(Optional.of(0), List.of()));
        assertThrows(IllegalArgumentException.class, () -> lock("com.example.EmptyTest", List.of()));
        assertThrows(IllegalArgumentException.class, () -> lock(
                "com.example.DuplicateTest",
                List.of(new LocalId("database"), new LocalId("database"))));
        assertThrows(IllegalArgumentException.class, () -> suite(
                Optional.empty(),
                List.of(
                        lock("com.example.DbTest", List.of(new LocalId("one"))),
                        lock("com.example.DbTest", List.of(new LocalId("two"))))));
        assertThrows(IllegalArgumentException.class, () -> new JavaBinaryClassName("*SmokeTest"));
        assertThrows(IllegalArgumentException.class, () -> new TestClassPattern("com/example/SmokeTest"));
    }

    @Test
    void rejectsEmptyOrDuplicateSelection() {
        assertThrows(IllegalArgumentException.class, () -> suite(Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredTestSuite(
                List.of(new TestClassPattern("*Test"), new TestClassPattern("*Test")),
                List.of(), List.of(), List.of(), Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredTestSuite(
                List.of(), List.of(), List.of("fast", "fast"), List.of(), Optional.empty(), List.of()));
    }

    private static AuthoredTestSuite suite(
            Optional<Integer> workers, List<AuthoredTestSuite.Lock> locks) {
        return new AuthoredTestSuite(
                List.of(), List.of(), List.of(), List.of(), workers, locks);
    }

    private static AuthoredTestSuite.Lock lock(String className, List<LocalId> resources) {
        return new AuthoredTestSuite.Lock(new JavaBinaryClassName(className), resources);
    }
}
