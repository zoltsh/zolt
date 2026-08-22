package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildException;
import sh.zolt.doctor.JdkStatus;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class CompilerPlatformApiTest {
    @Test
    void rejectsHostModeForModularSourceSet() {
        List<Path> sources = List.of(
                Path.of("src/main/java/module-info.java"),
                Path.of("src/main/java/com/example/Main.java"));

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompilerPlatformApi.rejectModularHost(true, sources, "main"));

        assertTrue(exception.getMessage().contains("module-info.java"), exception.getMessage());
        assertTrue(exception.getMessage().contains("[compiler] jdkApi"), exception.getMessage());
    }

    @Test
    void allowsReleaseModeForModularSourceSet() {
        List<Path> sources = List.of(Path.of("src/main/java/module-info.java"));

        CompilerPlatformApi.rejectModularHost(false, sources, "main");
    }

    @Test
    void allowsHostModeForNonModularSourceSet() {
        List<Path> sources = List.of(Path.of("src/main/java/com/example/Main.java"));

        CompilerPlatformApi.rejectModularHost(true, sources, "main");
    }

    @Test
    void determinismWarningNamesBuildJdkFeatureVersionInHostMode() {
        JdkStatus jdkStatus = new JdkStatus(
                Optional.empty(),
                Optional.of(Path.of("java")),
                Optional.of(Path.of("javac")),
                Optional.of(Path.of("jar")),
                Optional.of("17.0.9"),
                "8");

        String warning = CompilerPlatformApi.determinismWarning(true, "test", jdkStatus);

        assertTrue(warning.contains("[compiler.test] jdkApi"), warning);
        assertTrue(warning.contains("build JDK feature version 17"), warning);
        assertTrue(warning.contains("reproducibility"), warning);
    }

    @Test
    void determinismWarningEmptyInReleaseMode() {
        JdkStatus jdkStatus = new JdkStatus(
                Optional.empty(),
                Optional.of(Path.of("java")),
                Optional.of(Path.of("javac")),
                Optional.of(Path.of("jar")),
                Optional.of("17.0.9"),
                "8");

        assertEquals("", CompilerPlatformApi.determinismWarning(false, "main", jdkStatus));
    }

    @Test
    void modularDetectionIgnoresNonModuleInfoFiles() {
        assertFalse(CompilerPlatformApi.isModularSourceSet(List.of(
                Path.of("src/main/java/com/example/ModuleInfoHelper.java"))));
        assertTrue(CompilerPlatformApi.isModularSourceSet(List.of(
                Path.of("src/main/java/module-info.java"))));
    }
}
