package sh.zolt.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class TestSuiteSettingsTest {
    @Test
    void defaultsToSerialExecution() {
        TestSuiteSettings settings = TestSuiteSettings.empty();

        assertEquals(false, settings.parallelSafe());
        assertEquals(1, settings.maxWorkers());
        assertEquals(Map.of(), settings.resourceLocks());
    }

    @Test
    void ordersResourceLocksDeterministically() {
        TestSuiteSettings settings = new TestSuiteSettings(
                List.of("*Test"),
                List.of(),
                List.of(),
                List.of(),
                true,
                4,
                Map.of(
                        "com.example.KafkaTest",
                        List.of("kafka"),
                        "com.example.DbTest",
                        List.of("database", "schema")));

        assertEquals(
                List.of("com.example.DbTest", "com.example.KafkaTest"),
                settings.resourceLocks().keySet().stream().toList());
        assertEquals(List.of("database", "schema"), settings.resourceLocks().get("com.example.DbTest"));
    }

    @Test
    void rejectsInvalidMaxWorkers() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new TestSuiteSettings(
                        List.of(), List.of(), List.of(), List.of(), true, 0, Map.of()));

        assertEquals("test.suites.workers must be greater than zero.", exception.getMessage());
    }

    @Test
    void rejectsEmptyResourceLockLists() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new TestSuiteSettings(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        true,
                        2,
                        Map.of("com.example.DbTest", List.of())));

        assertEquals(
                "test.suites.locks.com.example.DbTest requires at least one resource lock.",
                exception.getMessage());
    }
}
