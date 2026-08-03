package sh.zolt.build.coverage;

import sh.zolt.build.CoverageException;
import sh.zolt.build.JavaRunException;
import sh.zolt.build.run.JavaRunResult;
import sh.zolt.build.run.JavaRunner;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.classpath.Classpath;
import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.build.testruntime.TestReportSettings;
import sh.zolt.build.testruntime.TestRunResult;
import sh.zolt.build.testruntime.TestRunService;
import sh.zolt.resolve.ResolveService;
import sh.zolt.test.shard.TestShardSpec;
import sh.zolt.test.TestSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class CoverageService {
    private static final String JACOCO_CLI_MAIN_CLASS = "org.jacoco.cli.internal.Main";
    private static final PackageId JACOCO_AGENT_PACKAGE = new PackageId("org.jacoco", "org.jacoco.agent");
    private static final PackageId JACOCO_CLI_PACKAGE = new PackageId("org.jacoco", "org.jacoco.cli");

    private final CoverageTestRunner testRunner;
    private final ZoltLockfileReader lockfileReader;
    private final JdkChecker jdkDetector;
    private final JavaRunner javaRunner;
    private final CoverageToolingResolver toolingResolver;

    public CoverageService() {
        this(
                new TestRunService()::runTests,
                new ZoltLockfileReader(),
                new JdkDetector(),
                new JavaRunner(),
                new ResolveService()::resolveWithCoverageTooling);
    }

    /** Coverage whose instrumented worker runs via {@code testRunService} (carrying any test-runtime toolchain) while jacoco reports run on {@code jdkDetector}. */
    public CoverageService(TestRunService testRunService, JdkChecker jdkDetector) {
        this(
                testRunService::runTests,
                new ZoltLockfileReader(),
                jdkDetector,
                new JavaRunner(),
                new ResolveService()::resolveWithCoverageTooling);
    }

    CoverageService(
            CoverageTestRunner testRunner,
            ZoltLockfileReader lockfileReader,
            JdkChecker jdkDetector,
            JavaRunner javaRunner,
            CoverageToolingResolver toolingResolver) {
        this.testRunner = testRunner;
        this.lockfileReader = lockfileReader;
        this.jdkDetector = jdkDetector;
        this.javaRunner = javaRunner;
        this.toolingResolver = toolingResolver;
    }

    public CoverageResult runCoverage(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            TestSelection selection,
            CoverageReportSettings reportSettings,
            List<String> cliEvents) {
        return runCoverage(projectDirectory, config, cacheRoot, selection, reportSettings, cliEvents, "all", null);
    }

    public CoverageResult runCoverage(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            TestSelection selection,
            CoverageReportSettings reportSettings,
            List<String> cliEvents,
            String suiteName,
            TestShardSpec shard) {
        CoverageReportSettings settings = reportSettings == null
                ? CoverageReportSettings.defaultsForOutputRoot(config.build().outputRoot())
                : reportSettings;
        settings = settings.forShard(suiteName, shard);
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        toolingResolver.resolve(projectRoot, config, cacheRoot);
        CoverageTooling tooling = lockedCoverageTooling(projectRoot, cacheRoot);

        Path execFile = settings.absoluteExecFile(projectRoot);
        createParent(execFile);
        TestJvmArguments coverageJvmArguments = coverageJvmArguments(tooling.agentJar(), execFile, false);
        TestRunResult testResult = testRunner.runTests(
                projectRoot,
                config,
                cacheRoot,
                selection,
                coverageJvmArguments,
                settings.testReports(),
                cliEvents,
                suiteName,
                shard);
        mergeWorkerExecFilesIfPresent(projectRoot, config, execFile, tooling.cliClasspath());
        JavaRunResult reportResult = runReport(projectRoot, config, settings, execFile, tooling.cliClasspath());
        return new CoverageResult(
                testResult,
                reportResult.output(),
                execFile,
                settings.absoluteXmlReport(projectRoot),
                settings.absoluteHtmlDirectory(projectRoot));
    }

    public CoverageTooling lockedCoverageTooling(Path lockfileDirectory, Path cacheRoot) {
        Path lockfileRoot = lockfileDirectory.toAbsolutePath().normalize();
        List<ResolvedClasspathPackage> coveragePackages = coveragePackages(lockfileRoot, cacheRoot);
        Path agentJar = coverageArtifact(coveragePackages, JACOCO_AGENT_PACKAGE)
                .orElseThrow(() -> missingTool("org.jacoco:org.jacoco.agent"));
        if (!agentJar.getFileName().toString().contains("-runtime")) {
            throw new CoverageException(
                    "Coverage requires locked Jacoco runtime agent artifact `org.jacoco:org.jacoco.agent:runtime`. "
                            + "Run `zolt resolve` to refresh coverage tooling.");
        }
        List<Path> cliClasspath = coveragePackages.stream()
                .map(dependency -> dependency.resolvedPackage().jarPath().toAbsolutePath().normalize())
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        if (coverageArtifact(coveragePackages, JACOCO_CLI_PACKAGE).isEmpty()) {
            throw missingTool("org.jacoco:org.jacoco.cli");
        }
        return new CoverageTooling(agentJar, cliClasspath);
    }

    public TestJvmArguments coverageJvmArguments(Path agentJar, Path execFile, boolean append) {
        return new TestJvmArguments(List.of(
                "-javaagent:"
                        + agentJar.toAbsolutePath().normalize()
                        + "=destfile="
                        + execFile.toAbsolutePath().normalize()
                        + ",append="
                        + append));
    }

    public JavaRunResult runReport(
            Path projectRoot,
            ProjectConfig config,
            CoverageReportSettings settings,
            Path execFile,
            List<Path> cliClasspath) {
        return runReport(
                projectRoot,
                config,
                settings,
                execFile,
                cliClasspath,
                List.of(projectRoot.resolve(config.build().output()).normalize()),
                config.build().sourceRoots().stream()
                        .map(root -> projectRoot.resolve(root).normalize())
                        .toList());
    }

    public JavaRunResult runReport(
            Path projectRoot,
            ProjectConfig config,
            CoverageReportSettings settings,
            Path execFile,
            List<Path> cliClasspath,
            List<Path> classfileRoots,
            List<Path> sourceRoots) {
        JdkStatus jdkStatus = jdkDetector.detect(config.project().java());
        if (!jdkStatus.ok()) {
            throw new CoverageException("JDK check failed. " + String.join(" ", jdkStatus.problems()));
        }
        List<String> arguments = new ArrayList<>();
        arguments.add("report");
        arguments.add(execFile.toString());
        for (Path classfileRoot : classfileRoots) {
            arguments.add("--classfiles");
            arguments.add(classfileRoot.toAbsolutePath().normalize().toString());
        }
        for (Path sourceRoot : sourceRoots) {
            arguments.add("--sourcefiles");
            arguments.add(sourceRoot.toAbsolutePath().normalize().toString());
        }
        settings.absoluteXmlReport(projectRoot).ifPresent(path -> {
            createParent(path);
            arguments.add("--xml");
            arguments.add(path.toString());
        });
        settings.absoluteHtmlDirectory(projectRoot).ifPresent(path -> {
            createDirectory(path);
            arguments.add("--html");
            arguments.add(path.toString());
        });
        try {
            return javaRunner.run(
                    jdkStatus.java().orElseThrow(),
                    new Classpath(cliClasspath),
                    JACOCO_CLI_MAIN_CLASS,
                    List.of(),
                    arguments);
        } catch (JavaRunException exception) {
            throw new CoverageException(
                    "Coverage report generation failed. Check Jacoco output, test classes, and source paths, then run `zolt coverage` again.\n"
                            + exception.getMessage(),
                    exception);
        }
    }

    public JavaRunResult mergeExecFiles(
            Path projectRoot,
            ProjectConfig config,
            Path destinationExecFile,
            List<Path> sourceExecFiles,
            List<Path> cliClasspath) {
        List<Path> inputs = sourceExecFiles.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        if (inputs.isEmpty()) {
            throw new CoverageException("Coverage merge requires at least one Jacoco execution data file.");
        }
        createParent(destinationExecFile);
        if (inputs.size() == 1) {
            try {
                Files.copy(inputs.getFirst(), destinationExecFile, StandardCopyOption.REPLACE_EXISTING);
                return new JavaRunResult(JACOCO_CLI_MAIN_CLASS, "Copied split coverage execution data\n");
            } catch (IOException exception) {
                throw new CoverageException(
                        "Coverage merge failed while copying split Jacoco execution data to "
                                + destinationExecFile
                                + ".",
                        exception);
            }
        }
        JdkStatus jdkStatus = jdkDetector.detect(config.project().java());
        if (!jdkStatus.ok()) {
            throw new CoverageException("JDK check failed. " + String.join(" ", jdkStatus.problems()));
        }
        List<String> arguments = new ArrayList<>();
        arguments.add("merge");
        inputs.stream().map(Path::toString).forEach(arguments::add);
        arguments.add("--destfile");
        arguments.add(destinationExecFile.toAbsolutePath().normalize().toString());
        try {
            return javaRunner.run(
                    jdkStatus.java().orElseThrow(),
                    new Classpath(cliClasspath),
                    JACOCO_CLI_MAIN_CLASS,
                    List.of(),
                    arguments);
        } catch (JavaRunException exception) {
            throw new CoverageException(
                    "Coverage merge failed. Check split Jacoco execution data, then run `zolt coverage` again.\n"
                            + exception.getMessage(),
                    exception);
        }
    }

    public void mergeWorkerExecFilesIfPresent(
            Path projectRoot,
            ProjectConfig config,
            Path execFile,
            List<Path> cliClasspath) {
        List<Path> workerExecFiles = workerExecFiles(execFile);
        if (!workerExecFiles.isEmpty()) {
            mergeExecFiles(projectRoot, config, execFile, workerExecFiles, cliClasspath);
        }
    }

    private static List<Path> workerExecFiles(Path execFile) {
        Path parent = execFile.getParent();
        if (parent == null) {
            return List.of();
        }
        Path workersDirectory = parent.resolve("workers");
        if (!Files.isDirectory(workersDirectory)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(workersDirectory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().equals(execFile.getFileName()))
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException exception) {
            throw new CoverageException(
                    "Could not inspect split coverage execution data under " + workersDirectory + ".",
                    exception);
        }
    }

    private List<ResolvedClasspathPackage> coveragePackages(Path projectDirectory, Path cacheRoot) {
        ZoltLockfile lockfile = lockfileReader.read(projectDirectory.resolve("zolt.lock"));
        List<ResolvedClasspathPackage> packages = LockfileClasspathPackageConverter.classpathPackages(lockfile, cacheRoot).stream()
                .filter(dependency -> dependency.scope() == DependencyScope.TOOL_COVERAGE)
                .toList();
        if (packages.isEmpty()) {
            throw new CoverageException(
                    "Coverage requires locked tooling in scope `tool-coverage`. Run `zolt resolve` to refresh Jacoco tooling, then run `zolt coverage` again.");
        }
        return packages;
    }

    private static Optional<Path> coverageArtifact(List<ResolvedClasspathPackage> packages, PackageId packageId) {
        return packages.stream()
                .filter(dependency -> dependency.resolvedPackage().packageId().equals(packageId))
                .map(dependency -> dependency.resolvedPackage().jarPath())
                .findFirst();
    }

    private static CoverageException missingTool(String coordinate) {
        return new CoverageException(
                "Coverage requires locked tooling artifact `" + coordinate + "` in scope `tool-coverage`. "
                        + "Run `zolt resolve` to refresh Jacoco tooling, then run `zolt coverage` again.");
    }

    private static void createParent(Path path) {
        Path parent = path.getParent();
        if (parent != null) {
            createDirectory(parent);
        }
    }

    private static void createDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException exception) {
            throw new CoverageException("Could not create coverage output directory " + path + ".", exception);
        }
    }

}
