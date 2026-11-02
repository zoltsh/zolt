package sh.zolt.build.testruntime.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.test.runtime.TestRunException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CurrentWorkerClasspathTest {
    @TempDir Path temp;

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

    @Test
    void installedLauncherFollowsRelativeLinkAndLinkedAncestorWithSpaces() throws IOException {
        Path root = temp.resolve("native installation with spaces");
        Path executable = file(root.resolve("versions/1.0.0/bin/zolt"));
        Path worker = file(root.resolve("versions/1.0.0/libexec/zolt-junit-worker.jar"));
        file(root.resolve("libexec/zolt-junit-worker.jar")); // A stale root worker must not win.
        Path launcher = root.resolve("bin/zolt");
        Files.createDirectories(launcher.getParent());
        Files.createSymbolicLink(launcher, Path.of("../versions/1.0.0/bin/zolt"));
        Path alias = Files.createSymbolicLink(temp.resolve("linked ancestor with spaces"), root);

        assertEquals(List.of(worker.toRealPath()), currentWorkerClasspath.bundledWorkerClasspath(executable));
        assertEquals(List.of(worker.toRealPath()), currentWorkerClasspath.bundledWorkerClasspath(alias.resolve("bin/zolt")));
    }

    @Test
    void launcherRetargetDiscoversNewVersionWorker() throws IOException {
        Path first = file(temp.resolve("versions/1.0.0/bin/zolt"));
        Path firstWorker = file(temp.resolve("versions/1.0.0/libexec/zolt-junit-worker.jar"));
        Path second = file(temp.resolve("versions/1.0.1/bin/zolt"));
        Path secondWorker = file(temp.resolve("versions/1.0.1/libexec/zolt-junit-worker.jar"));
        Path launcher = Files.createSymbolicLink(temp.resolve("zolt"), first);
        assertEquals(List.of(firstWorker.toRealPath()), currentWorkerClasspath.bundledWorkerClasspath(launcher));
        Files.delete(launcher);
        Files.createSymbolicLink(launcher, second);
        assertEquals(List.of(secondWorker.toRealPath()), currentWorkerClasspath.bundledWorkerClasspath(launcher));
    }

    @Test
    void standaloneExecutableRetainsAdjacentLibexecDiscovery() throws IOException {
        Path executable = file(temp.resolve("standalone/zolt"));
        Path worker = file(temp.resolve("standalone/libexec/zolt-junit-worker.jar"));
        assertEquals(List.of(worker.toRealPath()), currentWorkerClasspath.bundledWorkerClasspath(executable));
    }

    @Test
    void unavailableExecutableOrWorkerLeavesFallbackAndOverridesAvailable() throws IOException {
        Path executable = file(temp.resolve("bin/zolt"));
        assertTrue(currentWorkerClasspath.bundledWorkerClasspath(executable).isEmpty());
        file(temp.resolve("libexec/zolt-junit-worker.jar"));
        Files.delete(executable);
        assertTrue(currentWorkerClasspath.bundledWorkerClasspath(executable).isEmpty());
        Path dangling = Files.createSymbolicLink(temp.resolve("dangling"), executable);
        assertTrue(currentWorkerClasspath.bundledWorkerClasspath(dangling).isEmpty());
        Files.createDirectories(executable);
        assertTrue(currentWorkerClasspath.bundledWorkerClasspath(executable).isEmpty());
        assertEquals(List.of(Path.of("current.jar").toAbsolutePath()), currentWorkerClasspath.discover(
                "", "", () -> currentWorkerClasspath.bundledWorkerClasspath(executable), "current.jar", ":"));
        assertEquals(List.of(Path.of("override.jar").toAbsolutePath()), currentWorkerClasspath.discover(
                "override.jar", "", () -> currentWorkerClasspath.bundledWorkerClasspath(executable), "current.jar", ":"));
    }

    private Path file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "fixture");
    }
}
