package sh.zolt.build.testruntime;

import static sh.zolt.build.testruntime.TestRunServiceTestSupport.commandArgumentAfter;
import static sh.zolt.build.testruntime.TestRunServiceTestSupport.config;
import static sh.zolt.build.testruntime.TestRunServiceTestSupport.launcherClasspath;
import static sh.zolt.build.testruntime.TestRunServiceTestSupport.multiRootConfig;
import static sh.zolt.build.testruntime.TestRunServiceTestSupport.service;
import static sh.zolt.build.testruntime.TestRunServiceTestSupport.source;
import static sh.zolt.build.testruntime.TestRunServiceLockfileTestSupport.writeConsoleAndJbossLogManagerLockfile;
import static sh.zolt.build.testruntime.TestRunServiceLockfileTestSupport.writeConsoleLockfile;
import static sh.zolt.build.testruntime.TestRunServiceLockfileTestSupport.writeNonStandaloneConsoleLockfile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.run.JavaRunner;
import sh.zolt.build.testruntime.TestRunServiceTestSupport.CachingJdkChecker;
import sh.zolt.test.runtime.TestRunException;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.TestSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TestRunServiceTest {
    @TempDir
    private Path projectDir;

    @Test
    void runsJUnitConsoleWithTestRuntimeClasspath() throws IOException {
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
                new TestJvmArguments(List.of("-Dlibrary.mode=true", "--add-opens=java.base/java.lang=ALL-UNNAMED")));

        assertEquals("Tests successful\n", result.output());
        assertEquals("junit-console", result.testRunner());
        assertEquals(
                List.of("-Dlibrary.mode=true", "--add-opens=java.base/java.lang=ALL-UNNAMED"),
                result.testJvmArguments().values());
        assertEquals(3, result.testRuntimeClasspathEntries());
        assertEquals(1, result.testLauncherClasspathEntries());
        assertEquals(1, result.testDiscoveryScanRoots());
        List<String> command = commands.getFirst();
        String userDirProperty = "-Duser.dir=" + projectDir.toAbsolutePath().normalize();
        assertEquals("-Dlibrary.mode=true", command.get(1));
        assertEquals("--add-opens=java.base/java.lang=ALL-UNNAMED", command.get(2));
        assertEquals(userDirProperty, command.get(3));
        assertTrue(command.indexOf(userDirProperty) < command.indexOf("-classpath"));
        assertTrue(command.contains("org.junit.platform.console.ConsoleLauncher"));
        assertTrue(command.contains("execute"));
        assertTrue(command.contains("--disable-banner"));
        assertTrue(command.contains("--scan-class-path="
                + projectDir.resolve("target/test-classes").toAbsolutePath().normalize()));
        assertTrue(command.contains("--include-classname"));
        assertTrue(command.contains("^(Test.*|.+[.$]Test.*|.*Tests?)$"));
        assertTrue(command.contains(".*Spec"));
        assertTrue(command.contains("--details"));
        assertEquals("summary", commandArgumentAfter(command, "--details"));
        String launcherClasspath = launcherClasspath(command);
        assertTrue(launcherClasspath.contains("junit-platform-console-standalone-1.11.4.jar"));
        assertFalse(launcherClasspath.contains("target/test-classes"));
        assertFalse(launcherClasspath.contains("target/classes"));
        assertTrue(command.stream().anyMatch(value -> value.contains("target/test-classes")));
        assertTrue(command.stream().anyMatch(value -> value.contains("target/classes")));
        assertTrue(command.stream().anyMatch(value -> value.contains("junit-platform-console-standalone-1.11.4.jar")));
    }

    @Test
    void launcherWithoutTestEngineCannotReportSuccess() throws IOException {
        writeConsoleLockfile(projectDir);
        source(projectDir, "src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source(projectDir, "src/test/java/com/example/MainTest.java", "package com.example; public final class MainTest {}\n");
        TestRunService service = service((command, outputConsumer) -> new JavaRunner.ProcessResult(
                0,
                "Cannot create Launcher without at least one TestEngine; "
                        + "consider adding an engine implementation JAR to the classpath\n"));

        TestRunException exception = assertThrows(
                TestRunException.class,
                () -> service.runTests(projectDir, config(), projectDir.resolve("cache")));

        assertTrue(exception.getMessage().contains("No test engine is present"));
        assertTrue(exception.getMessage().contains("org.junit.jupiter:junit-jupiter"));
    }

    @Test
    void compilesAdditionalJavaTestRootsBeforeRunningJUnitConsole() throws IOException {
        writeConsoleLockfile(projectDir);
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return "hello";
                    }
                }
                """);
        source(projectDir, "src/test/java/com/example/MainTest.java", """
                package com.example;

                public final class MainTest {
                    public String message() {
                        return Main.message();
                    }
                }
                """);
        source(projectDir, "src/integration-test/java/com/example/MainIT.java", """
                package com.example;

                public final class MainIT {
                    public String message() {
                        return Main.message();
                    }
                }
                """);
        List<List<String>> commands = new ArrayList<>();
        TestRunService service = service((command, outputConsumer) -> {
            commands.add(command);
            return new JavaRunner.ProcessResult(0, "Tests successful\n");
        });

        TestRunResult result = service.runTests(projectDir, multiRootConfig(), projectDir.resolve("cache"));

        assertEquals(2, result.compileResult().sourceCount());
        assertTrue(Files.exists(projectDir.resolve("target/test-classes/com/example/MainTest.class")));
        assertTrue(Files.exists(projectDir.resolve("target/test-classes/com/example/MainIT.class")));
        List<String> command = commands.getFirst();
        assertTrue(command.contains("org.junit.platform.console.ConsoleLauncher"));
        assertTrue(command.contains("--scan-class-path="
                + projectDir.resolve("target/test-classes").toAbsolutePath().normalize()));
        assertTrue(command.stream().anyMatch(value -> value.contains("target/test-classes")));
        assertTrue(command.stream().anyMatch(value -> value.contains("junit-platform-console-standalone-1.11.4.jar")));
    }

    @Test
    void configuresJbossLogManagerWhenPresentOnTestClasspath() throws IOException {
        writeConsoleAndJbossLogManagerLockfile(projectDir);
        source(projectDir, "src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source(projectDir, "src/test/java/com/example/MainTest.java", "package com.example; public final class MainTest {}\n");
        List<List<String>> commands = new ArrayList<>();
        TestRunService service = service((command, outputConsumer) -> {
            commands.add(command);
            return new JavaRunner.ProcessResult(0, "Tests successful\n");
        });

        service.runTests(projectDir, config(), projectDir.resolve("cache"));

        List<String> command = commands.getFirst();
        assertEquals("-Duser.dir=" + projectDir.toAbsolutePath().normalize(), command.get(1));
        assertEquals("-Djava.util.logging.manager=org.jboss.logmanager.LogManager", command.get(2));
        assertTrue(command.indexOf("-Djava.util.logging.manager=org.jboss.logmanager.LogManager")
                < command.indexOf("-classpath"));
        String launcherClasspath = launcherClasspath(command);
        assertTrue(launcherClasspath.contains("junit-platform-console-standalone-1.11.4.jar"));
        assertTrue(launcherClasspath.contains("jboss-logmanager-3.1.2.Final.jar"));
        assertTrue(command.stream().anyMatch(value -> value.contains("jboss-logmanager-3.1.2.Final.jar")));
    }

    @Test
    void nonStandaloneConsoleLaunchesWithJUnitPlatformRuntimeClasspath() throws IOException {
        writeNonStandaloneConsoleLockfile(projectDir);
        source(projectDir, "src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source(projectDir, "src/test/java/com/example/MainTest.java", "package com.example; public final class MainTest {}\n");
        List<List<String>> commands = new ArrayList<>();
        TestRunService service = service((command, outputConsumer) -> {
            commands.add(command);
            return new JavaRunner.ProcessResult(0, "Tests successful\n");
        });

        TestRunResult result = service.runTests(projectDir, config(), projectDir.resolve("cache"));

        String launcherClasspath = launcherClasspath(commands.getFirst());
        assertEquals("junit-console", result.testRunner());
        assertEquals(10, result.testRuntimeClasspathEntries());
        assertEquals(7, result.testLauncherClasspathEntries());
        assertEquals(1, result.testDiscoveryScanRoots());
        assertTrue(launcherClasspath.contains("junit-platform-console-1.11.4.jar"));
        assertTrue(launcherClasspath.contains("junit-platform-reporting-1.11.4.jar"));
        assertTrue(launcherClasspath.contains("junit-platform-launcher-1.11.4.jar"));
        assertTrue(launcherClasspath.contains("junit-platform-engine-1.11.4.jar"));
        assertTrue(launcherClasspath.contains("junit-platform-commons-1.11.4.jar"));
        assertTrue(launcherClasspath.contains("apiguardian-api-1.1.2.jar"));
        assertTrue(launcherClasspath.contains("opentest4j-1.3.0.jar"));
        assertFalse(launcherClasspath.contains("junit-jupiter-engine-5.11.4.jar"));
        assertFalse(launcherClasspath.contains("target/test-classes"));
        assertTrue(commandArgumentAfter(commands.getFirst(), "--class-path").contains("junit-jupiter-engine-5.11.4.jar"));
        assertTrue(commandArgumentAfter(commands.getFirst(), "--class-path").contains("target/test-classes"));
    }

    @Test
    void sharesCachedJdkDetectionAcrossBuildCompileAndExecution() throws IOException {
        writeConsoleLockfile(projectDir);
        source(projectDir, "src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source(projectDir, "src/test/java/com/example/MainTest.java", "package com.example; public final class MainTest {}\n");
        CachingJdkChecker jdkChecker = new CachingJdkChecker();
        TestRunService service = service(
                (command, outputConsumer) -> new JavaRunner.ProcessResult(0, "Tests successful\n"),
                jdkChecker);

        service.runTests(projectDir, config(), projectDir.resolve("cache"));

        assertEquals(3, jdkChecker.detectCalls());
        assertEquals(1, jdkChecker.toolchainReads());
    }

}
