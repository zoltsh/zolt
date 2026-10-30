package sh.zolt.build.testruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.build.testruntime.TestRunServiceLockfileTestSupport.writeConsoleLockfile;
import static sh.zolt.build.testruntime.TestRunServiceTestSupport.config;
import static sh.zolt.build.testruntime.TestRunServiceTestSupport.service;
import static sh.zolt.build.testruntime.TestRunServiceTestSupport.source;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sh.zolt.build.clean.CleanService;
import sh.zolt.build.run.JavaRunner;

final class TestRunMutationTest {
    @TempDir
    private Path project;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cleanWaitsForDirectAndPrecompiledTestExecution(boolean precompiled) throws Exception {
        writeConsoleLockfile(project);
        source(project, "src/main/java/com/example/Main.java", "package com.example; public class Main {}\n");
        source(project, "src/test/java/com/example/MainTest.java", "package com.example; public class MainTest {}\n");
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestRunService service = service((command, output) -> {
            running.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Test execution was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return new JavaRunner.ProcessResult(0, "Tests successful\n");
        });
        var compiled = precompiled ? service.compileTests(project, config(), project.resolve("cache")) : null;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var run = executor.submit(() -> precompiled
                    ? service.runCompiledTests(project, config(), compiled.classpaths(), compiled.testCompileResult())
                    : service.runTests(project, config(), project.resolve("cache")));
            try {
                assertTrue(running.await(10, TimeUnit.SECONDS));
                var clean = executor.submit(() -> new CleanService().clean(project, config()));
                assertThrows(TimeoutException.class, () -> clean.get(1, TimeUnit.SECONDS));
                assertTrue(Files.exists(project.resolve("target/test-classes/com/example/MainTest.class")));
                release.countDown();
                assertEquals("Tests successful\n", run.get(10, TimeUnit.SECONDS).output());
                assertEquals(1, clean.get(10, TimeUnit.SECONDS).deletedCount());
            } finally {
                release.countDown();
            }
        }
    }
}
