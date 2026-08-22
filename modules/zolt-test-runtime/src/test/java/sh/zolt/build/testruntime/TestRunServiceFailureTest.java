package sh.zolt.build.testruntime;

import static sh.zolt.build.testruntime.TestRunServiceTestSupport.config;
import static sh.zolt.build.testruntime.TestRunServiceTestSupport.service;
import static sh.zolt.build.testruntime.TestRunServiceTestSupport.source;
import static sh.zolt.build.testruntime.TestRunServiceLockfileTestSupport.writeConsoleLockfile;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.build.JavaRunException;
import sh.zolt.build.run.JavaRunner;
import sh.zolt.test.runtime.TestRunException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TestRunServiceFailureTest {
    @TempDir
    private Path projectDir;

    @Test
    void missingConsoleJarProducesActionableError() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        source(projectDir, "src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source(projectDir, "src/test/java/com/example/MainTest.java", "package com.example; public final class MainTest {}\n");
        TestRunService service = service((command, outputConsumer) -> new JavaRunner.ProcessResult(0, ""));

        TestRunException exception = assertThrows(
                TestRunException.class,
                () -> service.runTests(projectDir, config(), cacheRoot));

        assertTrue(exception.getMessage().contains("JUnit Platform Console is not present"));
        assertTrue(exception.getMessage().contains("Run `zolt resolve`"));
        assertTrue(exception.getMessage().contains("test engines declared in [dependencies.test]"));
    }

    @Test
    void failingTestsReturnNonZeroThroughJavaRunner() throws IOException {
        writeConsoleLockfile(projectDir);
        source(projectDir, "src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source(projectDir, "src/test/java/com/example/MainTest.java", "package com.example; public final class MainTest {}\n");
        TestRunService service = service((command, outputConsumer) -> new JavaRunner.ProcessResult(2, "test failed\n"));

        JavaRunException exception = assertThrows(
                JavaRunException.class,
                () -> service.runTests(projectDir, config(), projectDir.resolve("cache")));

        assertTrue(exception.getMessage().contains("java exited with code 2"));
        assertTrue(exception.getMessage().contains("test failed"));
    }
}
