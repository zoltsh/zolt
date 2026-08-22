package sh.zolt.selfhost;

import static sh.zolt.selfhost.SelfCheckServiceTestSupport.buildResult;
import static sh.zolt.selfhost.SelfCheckServiceTestSupport.writeSelfHostingProject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.run.JavaRunResult;
import sh.zolt.build.nativeimage.NativeBuildResult;
import sh.zolt.build.nativeimage.NativeImageResult;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.run.RunPackageResult;
import sh.zolt.build.testruntime.compile.TestCompileResult;
import sh.zolt.build.testruntime.TestRunResult;
import sh.zolt.doctor.SelfHostingCheckService;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SelfCheckServiceNativeTest {
    @TempDir
    private Path tempDir;

    @Test
    void runsNativeSelfHostingPathWhenRequested() throws IOException {
        writeSelfHostingProject(tempDir, true);
        BuildResult buildResult = buildResult(tempDir, 12);
        PackageResult packageResult = new PackageResult(
                buildResult,
                tempDir.resolve("target/demo-0.1.0.jar"),
                46,
                true);
        NativeBuildResult nativeBuildResult = new NativeBuildResult(
                packageResult,
                new NativeImageResult(
                        tempDir.resolve("target/native/demo"),
                        tempDir.resolve("target/native/native-image.log"),
                        "native ok\n"));
        List<String> calls = new java.util.ArrayList<>();
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
                        new JavaRunResult("com.example.Main", "0.1.0\n")),
                (projectDirectory, config, cacheRoot, nativeImageExecutable) -> {
                    calls.add("native:" + nativeImageExecutable);
                    return nativeBuildResult;
                },
                (binary, arguments) -> {
                    calls.add("run-native:" + binary.getFileName() + ":" + arguments);
                    return new SelfCheckService.NativeBinaryRunResult(binary, "0.1.0\n");
                });

        SelfCheckResult result = service.check(
                tempDir,
                tempDir.resolve("cache"),
                false,
                true,
                Path.of("custom-native-image"));

        assertTrue(result.ok());
        assertEquals(List.of("native:custom-native-image", "run-native:demo:[--version]"), calls);
        assertEquals(List.of(
                        "doctor --self-hosting",
                        "resolve --locked",
                        "build",
                        "test",
                        "package",
                        "run packaged jar",
                        "native",
                        "run native binary"),
                result.steps().stream().map(SelfCheckResult.SelfCheckStep::name).toList());
        assertEquals("printed 0.1.0", result.steps().getLast().message());
    }

    @Test
    void failsWhenNativeBinaryDoesNotPrintVersion() throws IOException {
        writeSelfHostingProject(tempDir, true);
        BuildResult buildResult = buildResult(tempDir, 12);
        PackageResult packageResult = new PackageResult(
                buildResult,
                tempDir.resolve("target/demo-0.1.0.jar"),
                46,
                true);
        NativeBuildResult nativeBuildResult = new NativeBuildResult(
                packageResult,
                new NativeImageResult(
                        tempDir.resolve("target/native/demo"),
                        tempDir.resolve("target/native/native-image.log"),
                        "native ok\n"));
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
                        new JavaRunResult("com.example.Main", "0.1.0\n")),
                (projectDirectory, config, cacheRoot, nativeImageExecutable) -> nativeBuildResult,
                (binary, arguments) -> new SelfCheckService.NativeBinaryRunResult(binary, "usage\n"));

        SelfCheckResult result = service.check(
                tempDir,
                tempDir.resolve("cache"),
                false,
                true,
                Path.of("native-image"));

        assertFalse(result.ok());
        assertEquals("run native binary", result.steps().getLast().name());
        assertTrue(result.steps().getLast().message().contains("expected native binary to print only `0.1.0`"));
    }

}
