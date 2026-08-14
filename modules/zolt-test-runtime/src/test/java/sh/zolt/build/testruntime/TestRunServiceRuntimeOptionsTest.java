package sh.zolt.build.testruntime;

import static sh.zolt.build.testruntime.TestRunServiceTestSupport.commandArgumentAfter;
import static sh.zolt.build.testruntime.TestRunServiceTestSupport.config;
import static sh.zolt.build.testruntime.TestRunServiceTestSupport.configWithTestRuntime;
import static sh.zolt.build.testruntime.TestRunServiceTestSupport.service;
import static sh.zolt.build.testruntime.TestRunServiceTestSupport.source;
import static sh.zolt.build.testruntime.TestRunServiceLockfileTestSupport.createSymlink;
import static sh.zolt.build.testruntime.TestRunServiceLockfileTestSupport.writeConsoleLockfile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.run.JavaRunner;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.runtime.TestRunException;
import sh.zolt.test.TestSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TestRunServiceRuntimeOptionsTest {
    @TempDir
    private Path projectDir;

    @Test
    void passesReportsDirectoryToJUnitConsole() throws IOException {
        writeConsoleLockfile(projectDir);
        source(projectDir, "src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source(projectDir, "src/test/java/com/example/MainTest.java", "package com.example; public final class MainTest {}\n");
        List<List<String>> commands = new ArrayList<>();
        TestRunService service = service((command, outputConsumer) -> {
            commands.add(command);
            return new JavaRunner.ProcessResult(0, "Tests successful\n");
        });

        TestRunResult result = service.runTests(
                projectDir,
                config(),
                projectDir.resolve("cache"),
                TestSelection.empty(),
                TestJvmArguments.empty(),
                TestReportSettings.reportsDirectory(Path.of("target/test-reports")));

        Path reportsDirectory = projectDir.resolve("target/test-reports").toAbsolutePath().normalize();
        assertEquals(Optional.of(reportsDirectory), result.reportsDirectory());
        List<String> command = commands.getFirst();
        assertEquals(reportsDirectory.toString(), commandArgumentAfter(command, "--reports-dir"));
    }

    @Test
    void reportsDirectoryRejectsExistingSymlinkThatEscapesProject() throws IOException {
        writeConsoleLockfile(projectDir);
        source(projectDir, "src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source(projectDir, "src/test/java/com/example/MainTest.java", "package com.example; public final class MainTest {}\n");
        Path outside = Files.createTempDirectory(projectDir.getParent(), "outside-test-reports-");
        createSymlink(projectDir.resolve("target/test-reports"), outside);
        List<List<String>> commands = new ArrayList<>();
        TestRunService service = service((command, outputConsumer) -> {
            commands.add(command);
            return new JavaRunner.ProcessResult(0, "Tests successful\n");
        });

        TestRunException exception = assertThrows(
                TestRunException.class,
                () -> service.runTests(
                        projectDir,
                        config(),
                        projectDir.resolve("cache"),
                        TestSelection.empty(),
                        TestJvmArguments.empty(),
                        TestReportSettings.reportsDirectory(Path.of("target/test-reports"))));

        assertTrue(exception.getMessage().contains("--reports-dir"));
        assertTrue(exception.getMessage().contains("target/test-reports"));
        assertTrue(exception.getMessage().contains("resolved through symlinks"));
        assertTrue(commands.isEmpty());
    }

    @Test
    void reportsDirectoryFailurePointsToReports() throws IOException {
        writeConsoleLockfile(projectDir);
        source(projectDir, "src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source(projectDir, "src/test/java/com/example/MainTest.java", "package com.example; public final class MainTest {}\n");
        TestRunService service = service((command, outputConsumer) ->
                new JavaRunner.ProcessResult(1, "test failure\n"));

        TestRunException exception = assertThrows(
                TestRunException.class,
                () -> service.runTests(
                        projectDir,
                        config(),
                        projectDir.resolve("cache"),
                        TestSelection.empty(),
                        TestJvmArguments.empty(),
                        TestReportSettings.reportsDirectory(Path.of("target/test-reports"))));

        assertTrue(exception.getMessage().contains("test failure"));
        assertTrue(exception.getMessage().contains("Test reports: "
                + projectDir.resolve("target/test-reports").toAbsolutePath().normalize()));
    }

    @Test
    void failsBeforeLaunchingTestsWhenCachedTestJarDoesNotMatchLockfileHash() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        String checksum = "0".repeat(64);
        Path jar = cacheRoot.resolve(
                "blobs/v2/sha256/" + checksum + "/junit-platform-console-standalone-1.11.4.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "corrupted console jar bytes");
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 6

                [[package]]
                id = "org.junit.platform:junit-platform-console-standalone"
                version = "1.11.4"
                source = "maven-central"
                scope = "test"
                direct = false
                jar = "blobs/v2/sha256/%s/junit-platform-console-standalone-1.11.4.jar"
                jarSha256 = "%s"
                dependencies = []
                """.formatted(checksum, checksum));
        source(projectDir, "src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source(projectDir, "src/test/java/com/example/MainTest.java", "package com.example; public final class MainTest {}\n");
        TestRunService service = service((command, outputConsumer) -> {
            throw new AssertionError("test JVM should not be launched with a corrupted cached jar");
        });

        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> service.runTests(projectDir, config(), cacheRoot));

        assertTrue(exception.getMessage().contains(
                "Cached jar integrity check failed for org.junit.platform:junit-platform-console-standalone:1.11.4"));
    }

    @Test
    void appliesConfiguredTestRuntimeToJUnitConsole() throws IOException {
        writeConsoleLockfile(projectDir);
        source(projectDir, "src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source(projectDir, "src/test/java/com/example/MainTest.java", "package com.example; public final class MainTest {}\n");
        List<List<String>> commands = new ArrayList<>();
        List<Map<String, String>> environments = new ArrayList<>();
        JavaRunner.ProcessRunner processRunner = new JavaRunner.ProcessRunner() {
            @Override
            public JavaRunner.ProcessResult run(List<String> command, java.util.function.Consumer<String> outputConsumer) {
                throw new AssertionError("Environment-aware Java runner should be used for tests.");
            }

            @Override
            public JavaRunner.ProcessResult run(
                    List<String> command,
                    Map<String, String> environment,
                    java.util.function.Consumer<String> outputConsumer) {
                commands.add(command);
                environments.add(environment);
                return new JavaRunner.ProcessResult(0, "Tests successful\n");
            }
        };
        TestRunService service = service(processRunner);

        TestRunResult result = service.runTests(
                projectDir,
                configWithTestRuntime(),
                projectDir.resolve("cache"),
                TestSelection.empty(),
                new TestJvmArguments(List.of("-Dcli=true")));

        Path root = projectDir.toAbsolutePath().normalize();
        assertEquals(List.of(
                "-Dconfigured=true",
                "-Dlogs.dir=" + root.resolve("test-logs"),
                "-Dcli=true"), result.testJvmArguments().values());
        List<String> command = commands.getFirst();
        assertEquals("-Dconfigured=true", command.get(1));
        assertEquals("-Dlogs.dir=" + root.resolve("test-logs"), command.get(2));
        assertEquals("-Dcli=true", command.get(3));
        assertEquals("-Duser.dir=" + root, command.get(4));
        assertEquals(Map.of(
                "APP_HOME", root.toString(),
                "TZ", "America/Chicago"), environments.getFirst());
        assertEquals("tree", commandArgumentAfter(command, "--details"));
        assertEquals("ascii", commandArgumentAfter(command, "--details-theme"));
    }

    @Test
    void cliTestEventsEnableDetailedJUnitConsoleOutput() throws IOException {
        writeConsoleLockfile(projectDir);
        source(projectDir, "src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source(projectDir, "src/test/java/com/example/MainTest.java", "package com.example; public final class MainTest {}\n");
        List<List<String>> commands = new ArrayList<>();
        TestRunService service = service((command, outputConsumer) -> {
            commands.add(command);
            return new JavaRunner.ProcessResult(0, "Tests successful\n");
        });

        service.runTests(
                projectDir,
                config(),
                projectDir.resolve("cache"),
                TestSelection.empty(),
                TestJvmArguments.empty(),
                TestReportSettings.disabled(),
                List.of("failed", "passed", "failed"));

        List<String> command = commands.getFirst();
        assertEquals("tree", commandArgumentAfter(command, "--details"));
        assertEquals("ascii", commandArgumentAfter(command, "--details-theme"));
    }

}
