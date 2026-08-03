package sh.zolt.build.testruntime;

import static sh.zolt.build.testruntime.TestRunServiceLockfileTestSupport.writeConsoleLockfile;
import static sh.zolt.build.testruntime.TestRunServiceTestSupport.config;
import static sh.zolt.build.testruntime.TestRunServiceTestSupport.service;
import static sh.zolt.build.testruntime.TestRunServiceTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.junit.PlainJunitWorkerRunResult;
import sh.zolt.build.run.JavaRunner;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.framework.FrameworkTestRunner;
import sh.zolt.junit.JunitWorkerClient;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TestRunServiceWorkerSupportSourcesTest {
    @TempDir
    private Path projectDir;

    @Test
    void optInPlainJUnitWorkerAllowsSupportOnlySources() throws IOException {
        writeConsoleLockfile(projectDir);
        source(projectDir, "src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source(projectDir, "src/test/java/com/example/TestSupport.java", """
                package com.example;

                public final class TestSupport {
                    public static String fixture() {
                        return "fixture";
                    }
                }
                """);
        TestRunService service = service(
                (command, outputConsumer) -> new JavaRunner.ProcessResult(0, "direct java should not run\n"),
                new JdkDetector(),
                FrameworkTestRunner.none(),
                () -> List.of(Path.of("/zolt/zolt.jar")),
                (javaExecutable, workerClasspath, projectDirectory, testRuntimeClasspath, testOutputDirectory, testSelection, jvmArguments, environment, reportsDirectory, testEvents, profileDirectory) ->
                        new PlainJunitWorkerRunResult(
                                new JunitWorkerClient.WorkerRunResult(
                                        "[         0 tests found           ]\n",
                                        2),
                                12_000_000L,
                                34_000_000L),
                true);

        TestRunResult result = service.runTests(
                projectDir,
                config(),
                projectDir.resolve("cache"));

        assertEquals(1, result.compileResult().sourceCount());
        assertTrue(result.output().contains("0 tests found"));
    }
}
