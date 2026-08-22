package sh.zolt.build.packaging;

import sh.zolt.lockfile.ProjectLockfile;
import sh.zolt.build.BuildException;
import sh.zolt.build.BuildResult;
import sh.zolt.build.PackageException;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.build.discovery.SourceDiscoverer;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.build.fingerprint.BuildFingerprintCheck;
import sh.zolt.build.fingerprint.BuildFingerprintService;
import sh.zolt.build.generatedsource.GeneratedSourceProducerFingerprint;
import sh.zolt.build.generatedsource.GeneratedSourceProducerFingerprintService;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.GeneratedSourceKind;
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
    private final GeneratedSourceProducerFingerprintService
            generatedProducerFingerprintService;

    PackageTestCompileGate(
            ZoltLockfileReader lockfileReader,
            ClasspathBuilder classpathBuilder) {
        this(
                new SourceDiscoverer(),
                new BuildFingerprintService(),
                lockfileReader,
                classpathBuilder,
                new PackageRuntimeJarSelector(),
                new GeneratedSourceProducerFingerprintService());
    }

    PackageTestCompileGate(
            SourceDiscoverer sourceDiscoverer,
            BuildFingerprintService fingerprintService,
            ZoltLockfileReader lockfileReader,
            ClasspathBuilder classpathBuilder,
            PackageRuntimeJarSelector runtimeJarSelector,
            GeneratedSourceProducerFingerprintService
                    generatedProducerFingerprintService) {
        this.sourceDiscoverer = sourceDiscoverer;
        this.fingerprintService = fingerprintService;
        this.lockfileReader = lockfileReader;
        this.classpathBuilder = classpathBuilder;
        this.runtimeJarSelector = runtimeJarSelector;
        this.generatedProducerFingerprintService =
                generatedProducerFingerprintService;
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
                "[build.output].test",
                config.build().testOutput());
        if (!Files.isDirectory(testOutput)) {
            throw staleTestOutput(
                    testOutput,
                    "missing-output-directory");
        }
        List<ResolvedClasspathPackage> resolvedPackages =
                classpathPackages.orElseGet(() ->
                        classpaths.isEmpty()
                                        || generatedProducerClasspathRequired(
                                                config)
                                ? packagesFromLock(
                                        projectRoot,
                                        cacheRoot)
                                : List.of());
        ClasspathSet resolvedClasspaths = classpaths.orElseGet(() ->
                classpathBuilder.build(resolvedPackages));
        List<Path> testCompileEntries = new ArrayList<>();
        testCompileEntries.add(buildResult.outputDirectory());
        testCompileEntries.addAll(
                resolvedClasspaths.testCompile().entries());
        SourceDiscoveryResult sources =
                sourceDiscoverer.discover(projectRoot, config.build());
        List<GeneratedSourceProducerFingerprint>
                generatedProducerFingerprints =
                        generatedProducerFingerprints(
                                projectRoot,
                                config,
                                resolvedPackages);
        BuildFingerprintCheck check =
                fingerprintService.checkTestEvidenceCurrent(
                        projectRoot,
                        config,
                        ProjectLockfile.in(projectRoot),
                        sources,
                        generatedProducerFingerprints,
                        new Classpath(testCompileEntries),
                        resolvedClasspaths.testProcessor(),
                        testOutput,
                        ProjectPaths.output(
                                projectRoot,
                                "[compiler.generated].test",
                                config.compilerSettings()
                                        .generatedTestSources()));
        if (!check.current()) {
            throw staleTestOutput(testOutput, check.reason());
        }
    }

    private List<ResolvedClasspathPackage> packagesFromLock(
            Path projectRoot,
            Optional<Path> cacheRoot) {
        Path lockfilePath = ProjectLockfile.in(projectRoot);
        if (!Files.isRegularFile(lockfilePath)) {
            return List.of();
        }
        ZoltLockfile lockfile = lockfileReader.read(lockfilePath);
        if (lockfile.packages().isEmpty()) {
            return List.of();
        }
        Path artifacts = cacheRoot.orElseThrow(() ->
                PackageException.actionable(
                        "Cannot verify the tests JAR compile classpath without artifact cache access.",
                        "Run `zolt test`, then package with the normal `zolt package` command."));
        return runtimeJarSelector.allClasspathPackages(
                lockfile,
                artifacts);
    }

    private List<GeneratedSourceProducerFingerprint>
            generatedProducerFingerprints(
                    Path projectRoot,
                    ProjectConfig config,
                    List<ResolvedClasspathPackage> packages) {
        try {
            return generatedProducerFingerprintService
                    .fingerprintsTest(
                            projectRoot,
                            config,
                            packages);
        } catch (BuildException exception) {
            if (exception.actionableError() != null) {
                throw new PackageException(
                        exception.actionableError());
            }
            throw new PackageException(
                    "Could not fingerprint generated test producer: "
                            + exception.getMessage(),
                    exception);
        }
    }

    private static boolean generatedProducerClasspathRequired(
            ProjectConfig config) {
        return config.build().generatedTestSources().stream()
                .anyMatch(step ->
                        step.kind() == GeneratedSourceKind.OPENAPI
                                || step.kind()
                                                == GeneratedSourceKind.EXEC
                                        && !"process".equals(
                                                step.exec()
                                                        .tool()
                                                        .runner()));
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
