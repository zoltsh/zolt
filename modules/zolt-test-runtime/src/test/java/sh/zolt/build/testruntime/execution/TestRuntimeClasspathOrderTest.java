package sh.zolt.build.testruntime.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Design §9.2: compiled outputs precede resolved dependency entries on the test runtime classpath.
 *
 * <p>The provided lane is on the test lanes, so a resolved jar can carry a fully qualified name the
 * module also compiles. First entry wins in the JVM, so this order is what decides which class a
 * test actually loads — it is behavior, not cosmetics.
 */
final class TestRuntimeClasspathOrderTest {
    private static final Path TEST_CLASSES = Path.of("target/test-classes");
    private static final Path MAIN_CLASSES = Path.of("target/classes");

    @Test
    void compiledOutputsPrecedeResolvedDependencyJars() {
        List<Path> dependencies = List.of(
                Path.of("cache/quarkus-junit-3.33.2.jar"),
                Path.of("cache/junit-platform-console-standalone-1.11.4.jar"));

        List<Path> classpath = TestRuntimeClasspathOrder.compose(TEST_CLASSES, MAIN_CLASSES, dependencies);

        assertEquals(
                List.of(
                        TEST_CLASSES,
                        MAIN_CLASSES,
                        Path.of("cache/quarkus-junit-3.33.2.jar"),
                        Path.of("cache/junit-platform-console-standalone-1.11.4.jar")),
                classpath);
    }

    @Test
    void testOutputPrecedesMainOutputSoTestFixturesShadowMainClasses() {
        List<Path> classpath = TestRuntimeClasspathOrder.compose(TEST_CLASSES, MAIN_CLASSES, List.of());

        assertEquals(0, classpath.indexOf(TEST_CLASSES));
        assertEquals(1, classpath.indexOf(MAIN_CLASSES));
    }

    @Test
    void resolvedDependencyOrderIsPreservedExactly() {
        List<Path> dependencies = List.of(
                Path.of("cache/zeta.jar"), Path.of("cache/alpha.jar"), Path.of("cache/middle.jar"));

        List<Path> classpath = TestRuntimeClasspathOrder.compose(TEST_CLASSES, MAIN_CLASSES, dependencies);

        assertEquals(dependencies, classpath.subList(2, classpath.size()));
    }

    @Test
    void theComposedClasspathIsImmutable() {
        List<Path> classpath = TestRuntimeClasspathOrder.compose(TEST_CLASSES, MAIN_CLASSES, List.of());

        assertThrows(UnsupportedOperationException.class, () -> classpath.add(Path.of("late.jar")));
    }

    /** The launcher split must not reorder the entries it passes through to the worker. */
    @Test
    void workerClasspathKeepsCompiledOutputsAheadOfDependencyJars() {
        Path console = Path.of("cache/junit-platform-console-standalone-1.11.4.jar");
        Path provided = Path.of("cache/quarkus-junit-3.33.2.jar");
        List<Path> runnerClasspath =
                TestRuntimeClasspathOrder.compose(TEST_CLASSES, MAIN_CLASSES, List.of(provided, console));

        List<Path> workerClasspath = new JunitLauncherClasspath().workerClasspath(runnerClasspath);

        assertTrue(
                workerClasspath.indexOf(TEST_CLASSES) < workerClasspath.indexOf(provided),
                () -> "compiled test output must still win over a provided jar: " + workerClasspath);
        assertTrue(
                workerClasspath.indexOf(MAIN_CLASSES) < workerClasspath.indexOf(provided),
                () -> "compiled main output must still win over a provided jar: " + workerClasspath);
    }
}
