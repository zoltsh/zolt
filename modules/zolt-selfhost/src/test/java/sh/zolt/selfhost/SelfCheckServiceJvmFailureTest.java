package sh.zolt.selfhost;

import static sh.zolt.selfhost.SelfCheckServiceTestSupport.buildResult;
import static sh.zolt.selfhost.SelfCheckServiceTestSupport.writeSelfHostingProject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.run.JavaRunResult;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.run.RunPackageResult;
import sh.zolt.build.testruntime.compile.TestCompileResult;
import sh.zolt.build.testruntime.TestRunResult;
import sh.zolt.doctor.SelfHostingCheckService;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SelfCheckServiceJvmFailureTest {
    @TempDir
    private Path tempDir;

    @Test
    void stopsWhenReadinessCheckFails() throws IOException {
        writeSelfHostingProject(tempDir, false);
        SelfCheckService service = new SelfCheckService(
                new ManifestProjectConfigLoader(),
                new SelfHostingCheckService(),
                (projectDirectory, config, cacheRoot, offline) -> {
                    throw new AssertionError("resolve should not run");
                },
                (projectDirectory, config, cacheRoot, offline) -> {
                    throw new AssertionError("build should not run");
                },
                (projectDirectory, config, cacheRoot) -> {
                    throw new AssertionError("test should not run");
                },
                (projectDirectory, config, buildResult, cacheRoot) -> {
                    throw new AssertionError("package should not run");
                },
                (projectDirectory, config, cacheRoot, packageResult) -> {
                    throw new AssertionError("run package should not run");
                },
                (projectDirectory, config, cacheRoot, nativeImageExecutable) -> {
                    throw new AssertionError("native should not run");
                },
                (binary, arguments) -> {
                    throw new AssertionError("native binary should not run");
                });

        SelfCheckResult result = service.check(tempDir, tempDir.resolve("cache"), false);

        assertFalse(result.ok());
        assertEquals(1, result.steps().size());
        assertEquals("doctor --self-hosting", result.steps().getFirst().name());
        assertTrue(result.steps().getFirst().message().contains("JUnit Platform Console"));
    }

    @Test
    void failsWhenPackagedApplicationDoesNotPrintVersion() throws IOException {
        writeSelfHostingProject(tempDir, true);
        BuildResult buildResult = buildResult(tempDir, 12);
        PackageResult packageResult = new PackageResult(
                buildResult,
                tempDir.resolve("target/demo-0.1.0.jar"),
                46,
                true);
        SelfCheckService service = new SelfCheckService(
                new ManifestProjectConfigLoader(),
                new SelfHostingCheckService(),
                (projectDirectory, config, cacheRoot, offline) ->
                        new ResolveResult(3, 0, 0, projectDirectory.resolve("zolt.lock")),
                (projectDirectory, config, cacheRoot, offline) -> buildResult,
                (projectDirectory, config, cacheRoot) -> new TestRunResult(
                        new TestCompileResult(
                                buildResult,
                                34,
                                0,
                                projectDirectory.resolve("target/test-classes"),
                                ""),
                        "Tests passed\n"),
                (projectDirectory, config, suppliedBuildResult, cacheRoot) -> packageResult,
                (projectDirectory, config, cacheRoot, suppliedPackageResult) -> new RunPackageResult(
                        suppliedPackageResult,
                        new JavaRunResult("com.example.Main", "usage\n")),
                (projectDirectory, config, cacheRoot, nativeImageExecutable) -> {
                    throw new AssertionError("native should not run");
                },
                (binary, arguments) -> {
                    throw new AssertionError("native binary should not run");
                });

        SelfCheckResult result = service.check(tempDir, tempDir.resolve("cache"), false);

        assertFalse(result.ok());
        assertEquals("run packaged jar", result.steps().getLast().name());
        assertTrue(result.steps().getLast().message().contains("expected packaged application to print only `0.1.0`"));
    }
}
