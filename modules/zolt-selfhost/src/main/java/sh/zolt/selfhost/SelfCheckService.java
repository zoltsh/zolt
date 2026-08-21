package sh.zolt.selfhost;

import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.nativeimage.NativeBuildResult;
import sh.zolt.build.nativeimage.NativeBuildService;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.packaging.PackageService;
import sh.zolt.build.run.RunPackageResult;
import sh.zolt.build.run.RunPackageService;
import sh.zolt.build.testruntime.TestRunResult;
import sh.zolt.build.testruntime.TestRunService;
import sh.zolt.doctor.SelfHostingCheckResult;
import sh.zolt.doctor.SelfHostingCheckService;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SelfCheckService {
    private final ManifestProjectConfigLoader manifestLoader;
    private final SelfHostingCheckService selfHostingCheckService;
    private final LockedResolver lockedResolver;
    private final ProjectBuilder projectBuilder;
    private final TestRunner testRunner;
    private final ProjectPackager projectPackager;
    private final PackagedApplicationRunner packagedApplicationRunner;
    private final NativeBuilder nativeBuilder;
    private final NativeBinaryRunner nativeBinaryRunner;

    public SelfCheckService() {
        this(
                new ManifestProjectConfigLoader(),
                new SelfHostingCheckService(),
                (projectDirectory, config, cacheRoot, offline) -> new ResolveService()
                        .resolve(projectDirectory, config, cacheRoot, true, offline),
                (projectDirectory, config, cacheRoot, offline) -> new BuildService()
                        .build(projectDirectory, config, cacheRoot, offline),
                (projectDirectory, config, cacheRoot) -> new TestRunService()
                        .runTests(projectDirectory, config, cacheRoot),
                (projectDirectory, config, buildResult, cacheRoot) -> new PackageService()
                        .packageJar(projectDirectory, config, buildResult, cacheRoot),
                (projectDirectory, config, cacheRoot, packageResult) -> new RunPackageService()
                        .runPackage(projectDirectory, config, cacheRoot, List.of("--version")),
                (projectDirectory, config, cacheRoot, nativeImageExecutable) -> new NativeBuildService()
                        .buildNative(projectDirectory, config, cacheRoot, nativeImageExecutable),
                SelfCheckService::runNativeBinary);
    }

    SelfCheckService(
            ManifestProjectConfigLoader manifestLoader,
            SelfHostingCheckService selfHostingCheckService,
            LockedResolver lockedResolver,
            ProjectBuilder projectBuilder,
            TestRunner testRunner,
            ProjectPackager projectPackager,
            PackagedApplicationRunner packagedApplicationRunner,
            NativeBuilder nativeBuilder,
            NativeBinaryRunner nativeBinaryRunner) {
        this.manifestLoader = manifestLoader;
        this.selfHostingCheckService = selfHostingCheckService;
        this.lockedResolver = lockedResolver;
        this.projectBuilder = projectBuilder;
        this.testRunner = testRunner;
        this.projectPackager = projectPackager;
        this.packagedApplicationRunner = packagedApplicationRunner;
        this.nativeBuilder = nativeBuilder;
        this.nativeBinaryRunner = nativeBinaryRunner;
    }

    public SelfCheckResult check(Path projectDirectory, Path cacheRoot, boolean offline) {
        return check(projectDirectory, cacheRoot, offline, false, null);
    }

    public SelfCheckResult check(
            Path projectDirectory,
            Path cacheRoot,
            boolean offline,
            boolean nativeCheck,
            Path nativeImageExecutable) {
        Path root = projectDirectory.toAbsolutePath().normalize();
        List<SelfCheckResult.SelfCheckStep> steps = new ArrayList<>();

        if (WorkspaceSelfCheckService.usesRealWorkspace(root)) {
            return new WorkspaceSelfCheckService(nativeBinaryRunner)
                    .check(root, cacheRoot, offline, nativeCheck, nativeImageExecutable, steps);
        }

        ProjectConfig config;
        try {
            config = manifestLoader.load(root.resolve("zolt.toml"));
        } catch (RuntimeException exception) {
            steps.add(failed("config", exception.getMessage()));
            return new SelfCheckResult(steps);
        }

        SelfHostingCheckResult readiness = selfHostingCheckService.check(root, config);
        steps.add(new SelfCheckResult.SelfCheckStep(
                "doctor --self-hosting",
                readiness.ok(),
                readiness.ok()
                        ? "self-hosting readiness checks passed"
                        : firstFailure(readiness)));
        if (!readiness.ok()) {
            return new SelfCheckResult(steps);
        }

        ResolveResult resolveResult;
        try {
            resolveResult = lockedResolver.resolve(root, config, cacheRoot, offline);
            steps.add(new SelfCheckResult.SelfCheckStep(
                    "resolve --locked",
                    true,
                    "verified " + resolveResult.lockfilePath()));
        } catch (RuntimeException exception) {
            steps.add(failed("resolve --locked", exception.getMessage()));
            return new SelfCheckResult(steps);
        }

        BuildResult buildResult;
        try {
            buildResult = projectBuilder.build(root, config, cacheRoot, offline);
            steps.add(new SelfCheckResult.SelfCheckStep(
                    "build",
                    true,
                    "compiled " + buildResult.sourceCount() + " main source files"));
        } catch (RuntimeException exception) {
            steps.add(failed("build", exception.getMessage()));
            return new SelfCheckResult(steps);
        }

        try {
            TestRunResult testResult = testRunner.test(root, config, cacheRoot);
            steps.add(new SelfCheckResult.SelfCheckStep(
                    "test",
                    true,
                    "ran tests with " + testResult.compileResult().sourceCount() + " test source files"));
        } catch (RuntimeException exception) {
            steps.add(failed("test", exception.getMessage()));
            return new SelfCheckResult(steps);
        }

        PackageResult packageResult;
        try {
            packageResult = projectPackager.packageJar(root, config, buildResult, cacheRoot);
            steps.add(new SelfCheckResult.SelfCheckStep(
                    "package",
                    true,
                    "wrote " + packageResult.jarPath()));
        } catch (RuntimeException exception) {
            steps.add(failed("package", exception.getMessage()));
            return new SelfCheckResult(steps);
        }

        try {
            RunPackageResult runPackageResult = packagedApplicationRunner.run(root, config, cacheRoot, packageResult);
            String expectedVersion = config.project().version();
            String output = runPackageResult.javaRunResult().output();
            if (!output.trim().equals(expectedVersion)) {
                steps.add(failed(
                        "run packaged jar",
                        "expected packaged application to print only `" + expectedVersion + "` for --version"));
                return new SelfCheckResult(steps);
            }
            steps.add(new SelfCheckResult.SelfCheckStep(
                    "run packaged jar",
                    true,
                    "printed " + expectedVersion));
        } catch (RuntimeException exception) {
            steps.add(failed("run packaged jar", exception.getMessage()));
            return new SelfCheckResult(steps);
        }

        if (!nativeCheck) {
            return new SelfCheckResult(steps);
        }

        NativeBuildResult nativeBuildResult;
        try {
            nativeBuildResult = nativeBuilder.buildNative(root, config, cacheRoot, nativeImageExecutable);
            steps.add(new SelfCheckResult.SelfCheckStep(
                    "native",
                    true,
                    "built " + nativeBuildResult.nativeImageResult().outputBinary()));
        } catch (RuntimeException exception) {
            steps.add(failed("native", exception.getMessage()));
            return new SelfCheckResult(steps);
        }

        try {
            NativeBinaryRunResult nativeRunResult = nativeBinaryRunner.run(
                    nativeBuildResult.nativeImageResult().outputBinary(),
                    List.of("--version"));
            String expectedVersion = config.project().version();
            if (!nativeRunResult.output().trim().equals(expectedVersion)) {
                steps.add(failed(
                        "run native binary",
                        "expected native binary to print only `" + expectedVersion + "` for --version"));
                return new SelfCheckResult(steps);
            }
            steps.add(new SelfCheckResult.SelfCheckStep(
                    "run native binary",
                    true,
                    "printed " + expectedVersion));
        } catch (RuntimeException exception) {
            steps.add(failed("run native binary", exception.getMessage()));
        }
        return new SelfCheckResult(steps);
    }

    private static SelfCheckResult.SelfCheckStep failed(String name, String message) {
        return new SelfCheckResult.SelfCheckStep(name, false, message == null ? "check failed" : message);
    }

    private static String firstFailure(SelfHostingCheckResult readiness) {
        return readiness.checks().stream()
                .filter(check -> !check.ok())
                .map(check -> check.name() + ": " + check.message())
                .findFirst()
                .orElse("self-hosting readiness failed");
    }

    private static NativeBinaryRunResult runNativeBinary(Path binary, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(binary.toString());
        command.addAll(arguments);
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            byte[] buffer = new byte[8192];
            int read = process.getInputStream().read(buffer);
            while (read >= 0) {
                output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                read = process.getInputStream().read(buffer);
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException(
                        "native binary exited with code "
                                + exitCode
                                + ". Check the application output and try again.\n"
                                + output.toString().stripTrailing());
            }
            return new NativeBinaryRunResult(binary, output.toString());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not run native binary at "
                            + binary
                            + ". Check that `zolt native` produced an executable binary.",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("native binary run was interrupted. Try the self-check again.", exception);
        }
    }

    @FunctionalInterface
    interface LockedResolver {
        ResolveResult resolve(Path projectDirectory, ProjectConfig config, Path cacheRoot, boolean offline);
    }

    @FunctionalInterface
    interface ProjectBuilder {
        BuildResult build(Path projectDirectory, ProjectConfig config, Path cacheRoot, boolean offline);
    }

    @FunctionalInterface
    interface TestRunner {
        TestRunResult test(Path projectDirectory, ProjectConfig config, Path cacheRoot);
    }

    @FunctionalInterface
    interface ProjectPackager {
        PackageResult packageJar(Path projectDirectory, ProjectConfig config, BuildResult buildResult, Path cacheRoot);
    }

    @FunctionalInterface
    interface PackagedApplicationRunner {
        RunPackageResult run(Path projectDirectory, ProjectConfig config, Path cacheRoot, PackageResult packageResult);
    }

    @FunctionalInterface
    interface NativeBuilder {
        NativeBuildResult buildNative(
                Path projectDirectory,
                ProjectConfig config,
                Path cacheRoot,
                Path nativeImageExecutable);
    }

    @FunctionalInterface
    interface NativeBinaryRunner {
        NativeBinaryRunResult run(Path binary, List<String> arguments);
    }

    record NativeBinaryRunResult(Path binary, String output) {
    }
}
