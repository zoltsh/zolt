package sh.zolt.toolchain.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class JavaRuntimeProbeTest {
    @Test
    void parsesRuntimeInfoFromJavaOutput() {
        JavaRuntimeInfo info = JavaRuntimeProbe.parse("""
                    java.vendor = Eclipse Temurin
                    java.version = 21.0.2
                openjdk version "21.0.2" 2024-01-16
                """).runtime();

        assertEquals(Optional.of("21.0.2"), info.version());
        assertEquals(Optional.of("21"), info.featureVersion());
        assertEquals(Optional.of("Eclipse Temurin"), info.vendor());
    }

    @Test
    void parsesJavaHomeReportedByTheJvm() {
        JavaRuntimeProbe.Result result = JavaRuntimeProbe.parse("""
                    java.home = /opt/jdk-21
                    java.vendor = Eclipse Temurin
                    java.version = 21.0.2
                openjdk version "21.0.2" 2024-01-16
                """);

        assertEquals(Optional.of(Path.of("/opt/jdk-21")), result.javaHome());
        assertEquals(Optional.of("21"), result.runtime().featureVersion());
    }

    @Test
    void reportsNoJavaHomeWhenOutputOmitsIt() {
        JavaRuntimeProbe.Result result = JavaRuntimeProbe.parse("""
                openjdk version "21.0.2" 2024-01-16
                """);

        assertTrue(result.javaHome().isEmpty());
        assertEquals(Optional.of("21.0.2"), result.runtime().version());
    }

    @Test
    void readsJavaHomeFromTheRunningJvm() {
        JavaRuntimeProbe.Result result = JavaRuntimeProbe
                .read(Path.of(System.getProperty("java.home"), "bin", executable("java")))
                .orElseThrow();

        assertEquals(
                Optional.of(Path.of(System.getProperty("java.home"))),
                result.javaHome());
        assertTrue(result.runtime().featureVersion().isPresent());
    }

    @Test
    void returnsEmptyWhenJavaCannotRun() {
        assertTrue(JavaRuntimeProbe.read(Path.of("/no/such/jdk/bin/java")).isEmpty());
    }

    private static String executable(String name) {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? name + ".exe"
                : name;
    }
}
