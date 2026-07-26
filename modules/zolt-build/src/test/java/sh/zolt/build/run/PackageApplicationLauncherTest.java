package sh.zolt.build.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.RunPackageException;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.project.PackageMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class PackageApplicationLauncherTest {
    private final List<List<String>> commands = new ArrayList<>();
    private final PackageApplicationLauncher launcher =
            new PackageApplicationLauncher(new JavaRunner(
                    ":",
                    (command, output) -> {
                        commands.add(command);
                        return new JavaRunner.ProcessResult(0, "ok\n");
                    }));

    @Test
    void thinUsesTheArchiveAndRuntimeClasspath() {
        launcher.launch(
                Path.of("java"),
                result(PackageMode.THIN),
                List.of(Path.of("runtime.jar")),
                "com.example.Main",
                List.of("arg"));

        assertEquals(
                List.of(
                        "java",
                        "-classpath",
                        "target/app.jar:runtime.jar",
                        "com.example.Main",
                        "arg"),
                commands.getFirst());
    }

    @Test
    void selfContainedModesUseJavaJarWithoutOriginalRuntimeEntries() {
        for (PackageMode mode : List.of(
                PackageMode.SPRING_BOOT,
                PackageMode.SPRING_BOOT_WAR,
                PackageMode.UBER)) {
            launcher.launch(
                    Path.of("java"),
                    result(mode),
                    List.of(Path.of("deliberately-missing.jar")),
                    "com.example.Main",
                    List.of("arg"));
        }

        assertEquals(3, commands.size());
        for (List<String> command : commands) {
            assertEquals(
                    List.of(
                            "java",
                            "-jar",
                            "target/app.jar",
                            "arg"),
                    command);
            assertTrue(command.stream().noneMatch(
                    value -> value.contains("deliberately-missing")));
        }
    }

    @Test
    void nonRunnableModesShareActionableRejections() {
        assertRejected(
                PackageMode.WAR,
                "servlet container deployment");
        assertRejected(
                PackageMode.BOM,
                "dependencyManagement POM");
        assertRejected(
                PackageMode.QUARKUS,
                "Use `zolt run`");
    }

    private void assertRejected(
            PackageMode mode,
            String expectedMessage) {
        RunPackageException failure = assertThrows(
                RunPackageException.class,
                () -> launcher.launch(
                        Path.of("java"),
                        result(mode),
                        List.of(),
                        "com.example.Main",
                        List.of()));

        assertTrue(failure.getMessage().contains(expectedMessage));
    }

    private static PackageResult result(PackageMode mode) {
        if (mode == PackageMode.BOM) {
            return new PackageResult(
                    buildResult(),
                    mode,
                    Path.of("target/app.pom"),
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    false,
                    "dependencyManagement POM",
                    List.of(),
                    List.of());
        }
        return new PackageResult(
                buildResult(),
                mode,
                Path.of("target/app.jar"),
                Optional.empty(),
                1,
                true);
    }

    private static BuildResult buildResult() {
        return new BuildResult(
                Optional.empty(),
                1,
                0,
                Path.of("target/classes"),
                "");
    }
}
