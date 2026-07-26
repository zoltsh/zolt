package sh.zolt.build.packaging;

import sh.zolt.build.BuildResult;
import sh.zolt.build.PackageException;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.build.discovery.SourceDiscoverer;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.build.fingerprint.BuildFingerprintCheck;
import sh.zolt.build.fingerprint.BuildFingerprintService;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class PackageTestCompileGate {
    private final SourceDiscoverer sourceDiscoverer;
    private final BuildFingerprintService fingerprintService;
    private final ZoltLockfileReader lockfileReader;
    private final ClasspathBuilder classpathBuilder;
    private final PackageRuntimeJarSelector runtimeJarSelector;

    PackageTestCompileGate(
            ZoltLockfileReader lockfileReader,
            ClasspathBuilder classpathBuilder) {
        this(
                new SourceDiscoverer(),
                new BuildFingerprintService(),
                lockfileReader,
                classpathBuilder,
                new PackageRuntimeJarSelector());
    }

    PackageTestCompileGate(
            SourceDiscoverer sourceDiscoverer,
            BuildFingerprintService fingerprintService,
            ZoltLockfileReader lockfileReader,
            ClasspathBuilder classpathBuilder,
            PackageRuntimeJarSelector runtimeJarSelector) {
        this.sourceDiscoverer = sourceDiscoverer;
        this.fingerprintService = fingerprintService;
        this.lockfileReader = lockfileReader;
        this.classpathBuilder = classpathBuilder;
        this.runtimeJarSelector = runtimeJarSelector;
    }

    void requireCurrent(
            Path projectDirectory,
            ProjectConfig config,
            BuildResult buildResult,
            Optional<Path> cacheRoot,
            Optional<List<ResolvedClasspathPackage>> classpathPackages,
            Optional<ClasspathSet> classpaths) {
        if (!config.packageSettings().tests()) {
            return;
        }
        Path projectRoot = ProjectPaths.root(projectDirectory);
        Path testOutput = ProjectPaths.output(
                projectRoot,
                "[build].testOutput",
                config.build().testOutput());
        if (!Files.isDirectory(testOutput)) {
            throw staleTestOutput(
                    testOutput,
                    "missing-output-directory");
        }
        ClasspathSet resolvedClasspaths = classpaths.orElseGet(() ->
                classpathPackages
                        .map(classpathBuilder::build)
                        .orElseGet(() -> classpathsFromLock(
                                projectRoot,
                                cacheRoot)));
        List<Path> testCompileEntries = new ArrayList<>();
        testCompileEntries.add(buildResult.outputDirectory());
        testCompileEntries.addAll(
                resolvedClasspaths.testCompile().entries());
        SourceDiscoveryResult sources =
                sourceDiscoverer.discover(projectRoot, config.build());
        BuildFingerprintCheck check =
                fingerprintService.checkTestCompileCurrent(
                        projectRoot,
                        config,
                        projectRoot.resolve("zolt.lock"),
                        sources,
                        new Classpath(testCompileEntries),
                        resolvedClasspaths.testProcessor(),
                        testOutput,
                        ProjectPaths.output(
                                projectRoot,
                                "[compiler].generatedTestSources",
                                config.compilerSettings()
                                        .generatedTestSources()));
        if (!check.current()) {
            throw staleTestOutput(testOutput, check.reason());
        }
    }

    private ClasspathSet classpathsFromLock(
            Path projectRoot,
            Optional<Path> cacheRoot) {
        Path lockfilePath = projectRoot.resolve("zolt.lock");
        if (!Files.isRegularFile(lockfilePath)) {
            return classpathBuilder.build(List.of());
        }
        ZoltLockfile lockfile = lockfileReader.read(lockfilePath);
        if (lockfile.packages().isEmpty()) {
            return classpathBuilder.build(List.of());
        }
        Path artifacts = cacheRoot.orElseThrow(() ->
                PackageException.actionable(
                        "Cannot verify the tests JAR compile classpath without artifact cache access.",
                        "Run `zolt test`, then package with the normal `zolt package` command."));
        return classpathBuilder.build(
                runtimeJarSelector.allClasspathPackages(
                        lockfile,
                        artifacts));
    }

    private static PackageException staleTestOutput(
            Path testOutput,
            String reason) {
        return PackageException.actionable(
                "Cannot package a tests JAR because compiled test output is not current at "
                        + testOutput
                        + " ("
                        + reason
                        + ").",
                "Run `zolt test`, then retry `zolt package`.");
    }
}
