package sh.zolt.build.run;

import sh.zolt.build.BuildResultWithClasspaths;
import sh.zolt.build.BuildService;
import sh.zolt.build.RunException;
import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.classpath.Classpath;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.framework.FrameworkRunAugmenter;
import sh.zolt.framework.FrameworkRunResult;
import sh.zolt.lockfile.ProjectBuildContext;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class RunService {
    private final BuildService buildService;
    private final JdkChecker jdkDetector;
    private final JavaRunner javaRunner;
    private final FrameworkRunAugmenter frameworkRunAugmenter;

    public RunService() {
        this(new JdkDetector());
    }

    public RunService(JdkChecker jdkDetector) {
        this(jdkDetector, FrameworkRunAugmenter.none());
    }

    public RunService(FrameworkRunAugmenter frameworkRunAugmenter) {
        this(new JdkDetector(), frameworkRunAugmenter);
    }

    public RunService(JdkChecker jdkDetector, FrameworkRunAugmenter frameworkRunAugmenter) {
        this(
                new BuildService(jdkDetector),
                jdkDetector,
                new JavaRunner(),
                frameworkRunAugmenter);
    }

    RunService(
            BuildService buildService,
            JdkChecker jdkDetector,
            JavaRunner javaRunner,
            FrameworkRunAugmenter frameworkRunAugmenter) {
        this.buildService = buildService;
        this.jdkDetector = jdkDetector;
        this.javaRunner = javaRunner;
        this.frameworkRunAugmenter = frameworkRunAugmenter;
    }

    public RunService withJdkChecker(JdkChecker jdkChecker) {
        Objects.requireNonNull(jdkChecker, "jdkChecker");
        return new RunService(
                new BuildService(jdkChecker),
                jdkChecker,
                javaRunner,
                frameworkRunAugmenter);
    }

    public RunResult run(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            List<String> arguments) {
        return run(projectDirectory, config, cacheRoot, arguments, ignored -> {
        });
    }

    public RunResult run(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            List<String> arguments,
            Consumer<String> outputConsumer) {
        return run(
                projectDirectory,
                config,
                cacheRoot,
                arguments,
                outputConsumer,
                new VerifiedArtifactIndex());
    }

    public RunResult run(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            List<String> arguments,
            Consumer<String> outputConsumer,
            VerifiedArtifactIndex artifactIndex) {
        if (config.packageSettings().mode() == sh.zolt.project.PackageMode.BOM) {
            throw new RunException(
                    "Package mode `bom` publishes a dependencyManagement POM and has nothing to run. "
                            + "Import the BOM from an application module via [platforms] instead.");
        }
        boolean frameworkRunEnabled = frameworkRunAugmenter.isEnabled(config);
        String mainClass = frameworkRunEnabled
                ? null
                : config.project().main().orElseThrow(() -> new RunException(
                        "No main class is configured. Add [project].main to zolt.toml."));
        BuildResultWithClasspaths buildResult = buildService.buildWithClasspaths(
                projectDirectory,
                config,
                cacheRoot,
                false,
                artifactIndex);
        // Standalone lane: RunCommand routes a member to WorkspaceRunService before reaching here, so
        // this project directory is its own lock root and may declare itself one (design §4.5).
        Optional<FrameworkRunResult> frameworkRunResult = frameworkRunAugmenter.augmentIfEnabled(
                ProjectBuildContext.standalone(projectDirectory), config, cacheRoot);

        JdkStatus jdkStatus = jdkDetector.detect(config.project().java());
        if (!jdkStatus.ok()) {
            throw new RunException("JDK check failed. " + String.join(" ", jdkStatus.problems()));
        }
        if (frameworkRunResult.isPresent()) {
            FrameworkRunResult runResult = frameworkRunResult.orElseThrow();
            JavaRunResult javaRunResult = javaRunner.runJar(
                    jdkStatus.java().orElseThrow(),
                    runResult.runnerJar(),
                    runResult.runnerDescription(),
                    arguments,
                    outputConsumer);
            return new RunResult(buildResult.buildResult(), javaRunResult);
        }
        if (frameworkRunEnabled) {
            throw new RunException("Framework run augmenter was not configured for the enabled framework.");
        }

        List<Path> runtimeEntries = new ArrayList<>();
        runtimeEntries.add(buildResult.buildResult().outputDirectory());
        runtimeEntries.addAll(buildResult.classpaths().runtime().entries());
        JavaRunResult javaRunResult = javaRunner.run(
                jdkStatus.java().orElseThrow(),
                new Classpath(runtimeEntries),
                mainClass,
                arguments,
                outputConsumer);
        return new RunResult(buildResult.buildResult(), javaRunResult);
    }
}
