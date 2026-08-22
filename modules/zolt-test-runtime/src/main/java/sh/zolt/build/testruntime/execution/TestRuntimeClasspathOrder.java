package sh.zolt.build.testruntime.execution;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Composes the test runtime classpath in its load-bearing order: the module's own compiled outputs
 * first, then resolved dependency entries in resolution order.
 *
 * <p>Design §9.2: the test lanes include the provided lane, so a resolved jar may legitimately carry
 * the same fully qualified name as a class the module itself compiled. The JVM resolves a duplicate
 * name by classpath position — first entry wins — so compiled outputs must precede dependency jars.
 * A test fixture that deliberately stands in for a third-party class depends on exactly this, and so
 * does a module class that must win over a stale copy inside a jar. Every mainstream build tool
 * orders the test classpath this way; making it explicit here keeps it from being re-ordered by
 * accident now that the provided lane makes such collisions reachable.
 */
final class TestRuntimeClasspathOrder {
    private TestRuntimeClasspathOrder() {
    }

    static List<Path> compose(
            Path testOutputDirectory,
            Path mainOutputDirectory,
            List<Path> dependencyEntries) {
        Objects.requireNonNull(testOutputDirectory, "Test output directory is required.");
        Objects.requireNonNull(mainOutputDirectory, "Main output directory is required.");
        Objects.requireNonNull(dependencyEntries, "Resolved dependency entries are required.");
        List<Path> classpath = new ArrayList<>(dependencyEntries.size() + 2);
        classpath.add(testOutputDirectory);
        classpath.add(mainOutputDirectory);
        classpath.addAll(dependencyEntries);
        return List.copyOf(classpath);
    }
}
