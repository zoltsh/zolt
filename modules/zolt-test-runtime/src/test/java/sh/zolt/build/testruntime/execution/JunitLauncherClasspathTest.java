package sh.zolt.build.testruntime.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.test.runtime.TestJvmArguments;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class JunitLauncherClasspathTest {
    private final JunitLauncherClasspath launcherClasspath = new JunitLauncherClasspath();

    @Test
    void standaloneConsoleLaunchesWithStandaloneJarAndJbossLogManagerOnly() {
        List<Path> runnerClasspath = List.of(
                Path.of("target/test-classes"),
                Path.of("target/classes"),
                Path.of("cache/junit-platform-console-standalone-1.11.4.jar"),
                Path.of("cache/junit-jupiter-engine-5.11.4.jar"),
                Path.of("cache/jboss-logmanager-3.1.2.Final.jar"));

        List<Path> result = launcherClasspath.launcherClasspath(runnerClasspath);

        assertTrue(launcherClasspath.hasConsoleJar(runnerClasspath));
        assertEquals(List.of(
                Path.of("cache/junit-platform-console-standalone-1.11.4.jar"),
                Path.of("cache/jboss-logmanager-3.1.2.Final.jar")), result);
    }

    @Test
    void nonStandaloneConsoleLaunchesWithPlatformRuntimeAndSupportJarsOnly() {
        List<Path> runnerClasspath = List.of(
                Path.of("target/test-classes"),
                Path.of("target/classes"),
                Path.of("cache/junit-platform-console-1.11.4.jar"),
                Path.of("cache/junit-platform-reporting-1.11.4.jar"),
                Path.of("cache/junit-platform-launcher-1.11.4.jar"),
                Path.of("cache/junit-platform-engine-1.11.4.jar"),
                Path.of("cache/junit-platform-commons-1.11.4.jar"),
                Path.of("cache/apiguardian-api-1.1.2.jar"),
                Path.of("cache/opentest4j-1.3.0.jar"),
                Path.of("cache/junit-jupiter-engine-5.11.4.jar"),
                Path.of("cache/assertj-core-3.27.3.jar"));

        List<Path> result = launcherClasspath.launcherClasspath(runnerClasspath);

        assertTrue(launcherClasspath.hasConsoleJar(runnerClasspath));
        assertEquals(List.of(
                Path.of("cache/junit-platform-console-1.11.4.jar"),
                Path.of("cache/junit-platform-reporting-1.11.4.jar"),
                Path.of("cache/junit-platform-launcher-1.11.4.jar"),
                Path.of("cache/junit-platform-engine-1.11.4.jar"),
                Path.of("cache/junit-platform-commons-1.11.4.jar"),
                Path.of("cache/apiguardian-api-1.1.2.jar"),
                Path.of("cache/opentest4j-1.3.0.jar")), result);
    }

    @Test
    void workerPrioritizesStandalonePlatformOverEngineTransitives() {
        List<Path> runnerClasspath = List.of(
                Path.of("target/test-classes"),
                Path.of("target/classes"),
                Path.of("cache/junit-platform-commons-1.9.0.jar"),
                Path.of("cache/junit-platform-console-standalone-1.14.4.jar"),
                Path.of("cache/junit-platform-engine-1.9.0.jar"),
                Path.of("cache/spock-core-2.3-groovy-4.0.jar"));

        List<Path> result =
                launcherClasspath.workerClasspath(runnerClasspath);

        assertEquals(List.of(
                Path.of("cache/junit-platform-console-standalone-1.14.4.jar"),
                Path.of("target/test-classes"),
                Path.of("target/classes"),
                Path.of("cache/junit-platform-commons-1.9.0.jar"),
                Path.of("cache/junit-platform-engine-1.9.0.jar"),
                Path.of("cache/spock-core-2.3-groovy-4.0.jar")), result);
    }

    @Test
    void detectsMissingConsoleJar() {
        assertFalse(launcherClasspath.hasConsoleJar(List.of(
                Path.of("target/test-classes"),
                Path.of("cache/junit-jupiter-engine-5.11.4.jar"))));
    }

    @Test
    void jvmArgumentsAddUserDirAndJbossLogManagerAfterConfiguredArguments() {
        Path projectDirectory = Path.of("demo").toAbsolutePath().normalize();
        List<Path> runnerClasspath = List.of(
                Path.of("cache/junit-platform-console-standalone-1.11.4.jar"),
                Path.of("cache/jboss-logmanager-3.1.2.Final.jar"));

        List<String> result = launcherClasspath.jvmArguments(
                projectDirectory,
                runnerClasspath,
                new TestJvmArguments(List.of("-Dconfigured=true")));

        assertEquals(List.of(
                "-Dconfigured=true",
                "-Duser.dir=" + projectDirectory,
                "-Djava.util.logging.manager=org.jboss.logmanager.LogManager"), result);
    }
}
