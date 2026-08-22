package sh.zolt.selfhost;

import static sh.zolt.selfhost.SelfCheckServiceTestSupport.buildResult;
import static sh.zolt.selfhost.SelfCheckServiceTestSupport.writeSelfHostingProject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SelfCheckServiceTest {
    @TempDir
    private Path tempDir;

    @Test
    void runsJvmSelfHostingPathInOrder() throws IOException {
        writeSelfHostingProject(tempDir, true);
        List<String> calls = new ArrayList<>();
        SelfCheckService service = new SelfCheckService(
                new ManifestProjectConfigLoader(),
                new SelfHostingCheckService(),
                (projectDirectory, config, cacheRoot, offline) -> {
                    calls.add("resolve:" + offline);
                    return new ResolveResult(3, 0, 0, projectDirectory.resolve("zolt.lock"));
                },
                (projectDirectory, config, cacheRoot, offline) -> {
                    calls.add("build:" + offline);
                    return buildResult(projectDirectory, 12);
                },
                (projectDirectory, config, cacheRoot) -> {
                    calls.add("test");
                    sh.zolt.build.BuildResult buildResult = buildResult(projectDirectory, 12);
                    TestCompileResult compileResult = new TestCompileResult(
                            buildResult,
                            34,
                            0,
                            projectDirectory.resolve("target/test-classes"),
                            "");
                    return new TestRunResult(compileResult, "Tests passed\n");
                },
                (projectDirectory, config, buildResult, cacheRoot) -> {
                    calls.add("package:" + buildResult.sourceCount());
                    return new PackageResult(
                            buildResult,
                            projectDirectory.resolve("target/demo-0.1.0.jar"),
                            46,
                            true);
                },
                (projectDirectory, config, cacheRoot, packageResult) -> {
                    calls.add("run-package:" + packageResult.jarPath().getFileName());
                    return new RunPackageResult(
                            packageResult,
                            new JavaRunResult("com.example.Main", "0.1.0\n"));
                },
                (projectDirectory, config, cacheRoot, nativeImageExecutable) -> {
                    throw new AssertionError("native should not run");
                },
                (binary, arguments) -> {
                    throw new AssertionError("native binary should not run");
                });

        SelfCheckResult result = service.check(tempDir, tempDir.resolve("cache"), true);

        assertTrue(result.ok());
        assertEquals(List.of(
                        "resolve:true",
                        "build:true",
                        "test",
                        "package:12",
                        "run-package:demo-0.1.0.jar"),
                calls);
        assertEquals(List.of(
                        "doctor --self-hosting",
                        "resolve --locked",
                        "build",
                        "test",
                        "package",
                        "run packaged jar"),
                result.steps().stream().map(SelfCheckResult.SelfCheckStep::name).toList());
        assertEquals("printed 0.1.0", result.steps().getLast().message());
    }

    @Test
    void runsNativeSelfHostingPathWhenRequested() throws IOException {
        writeSelfHostingProject(tempDir, true);
        sh.zolt.build.BuildResult buildResult = buildResult(tempDir, 12);
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
        List<String> calls = new ArrayList<>();
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

}
