package sh.zolt.build.testruntime.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.test.runtime.TestRunException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CurrentWorkerClasspathTest {
    private final CurrentWorkerClasspath currentWorkerClasspath = new CurrentWorkerClasspath();

    @Test
    void discoversCurrentJvmClasspathEntries() {
        List<Path> result = currentWorkerClasspath.discover();

        assertTrue(!result.isEmpty());
        assertTrue(result.stream().allMatch(Path::isAbsolute));
    }

    @Test
    void normalizesNonBlankClasspathEntries() {
        List<Path> result = currentWorkerClasspath.discover("lib/zolt.jar::target/classes", ":");

        assertEquals(List.of(
                Path.of("lib/zolt.jar").toAbsolutePath().normalize(),
                Path.of("target/classes").toAbsolutePath().normalize()), result);
    }

    @Test
    void configuredPropertyWinsOverEnvironmentBundledAndCurrentClasspath() {
        List<Path> result = currentWorkerClasspath.discover(
                "lib/property-worker.jar",
                "lib/env-worker.jar",
                () -> List.of(Path.of("lib/bundled-worker.jar")),
                "lib/current.jar",
                ":");

        assertEquals(List.of(Path.of("lib/property-worker.jar").toAbsolutePath().normalize()), result);
    }

    @Test
    void configuredEnvironmentWinsWhenPropertyIsBlank() {
        List<Path> result = currentWorkerClasspath.discover(
                "",
                "lib/env-worker.jar",
                () -> List.of(Path.of("lib/bundled-worker.jar")),
                "lib/current.jar",
                ":");

        assertEquals(List.of(Path.of("lib/env-worker.jar").toAbsolutePath().normalize()), result);
    }

    @Test
    void bundledWorkerWinsWhenExplicitClasspathIsAbsent() {
        List<Path> result = currentWorkerClasspath.discover(
                "",
                "",
                () -> List.of(Path.of("lib/bundled-worker.jar")),
                "lib/current.jar",
                ":");

        assertEquals(List.of(Path.of("lib/bundled-worker.jar").toAbsolutePath().normalize()), result);
    }

    @Test
    void detectsOnlyDedicatedWorkerSourcesAsAvailable() {
        assertTrue(currentWorkerClasspath.dedicatedWorkerAvailable(
                "lib/property-worker.jar",
                "",
                List::of,
                ":"));
        assertTrue(currentWorkerClasspath.dedicatedWorkerAvailable(
                "",
                "lib/env-worker.jar",
                List::of,
                ":"));
        assertTrue(currentWorkerClasspath.dedicatedWorkerAvailable(
                "",
                "",
                () -> List.of(Path.of("lib/bundled-worker.jar")),
                ":"));
        assertTrue(!currentWorkerClasspath.dedicatedWorkerAvailable(
                "",
                "",
                List::of,
                ":"));
    }

    @Test
    void emptyClasspathIsActionable() {
        TestRunException exception = assertThrows(
                TestRunException.class,
                () -> currentWorkerClasspath.discover("", "", List::of, "", ":"));

        assertTrue(exception.getMessage().contains("Could not determine Zolt worker classpath"));
        assertTrue(exception.getMessage().contains(CurrentWorkerClasspath.PROPERTY));
        assertTrue(exception.getMessage().contains(CurrentWorkerClasspath.ENVIRONMENT));
        assertTrue(exception.getMessage().contains("java.class.path"));
    }
}
