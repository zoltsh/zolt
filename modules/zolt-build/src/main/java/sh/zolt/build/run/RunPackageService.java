package sh.zolt.build.run;

import sh.zolt.build.BuildResultWithClasspaths;
import sh.zolt.build.BuildService;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.packaging.PackageService;
import sh.zolt.build.RunPackageException;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.framework.FrameworkPackageAugmenter;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.provenance.BuildProvenanceSource;
import sh.zolt.resolve.ResolveService;
import java.nio.file.Path;
import java.util.List;

public final class RunPackageService {
    private final PackageService packageService;
    private final BuildService buildService;
    private final ClasspathBuilder classpathBuilder;
    private final JdkChecker jdkDetector;
    private final PackageApplicationLauncher applicationLauncher;

    public RunPackageService() {
        this(new JdkDetector());
    }

    public RunPackageService(JdkChecker jdkDetector) {
        this(jdkDetector, FrameworkPackageAugmenter.none());
    }

    public RunPackageService(FrameworkPackageAugmenter frameworkPackageAugmenter) {
        this(new JdkDetector(), frameworkPackageAugmenter);
    }

    public RunPackageService(JdkChecker jdkDetector, FrameworkPackageAugmenter frameworkPackageAugmenter) {
        this(jdkDetector, new ResolveService(), frameworkPackageAugmenter);
    }

    public RunPackageService(ResolveService resolveService, FrameworkPackageAugmenter frameworkPackageAugmenter) {
        this(resolveService, frameworkPackageAugmenter, new PackagePlanService());
    }

    public RunPackageService(
            ResolveService resolveService,
            FrameworkPackageAugmenter frameworkPackageAugmenter,
            PackagePlanService packagePlanService) {
        this(new JdkDetector(), resolveService, frameworkPackageAugmenter, packagePlanService);
    }

    public RunPackageService(
            ResolveService resolveService,
            FrameworkPackageAugmenter frameworkPackageAugmenter,
            PackagePlanService packagePlanService,
            BuildProvenanceSource provenanceSource) {
        this(new JdkDetector(), resolveService, frameworkPackageAugmenter, packagePlanService, provenanceSource);
    }

    public RunPackageService(
            JdkChecker jdkDetector,
            ResolveService resolveService,
            FrameworkPackageAugmenter frameworkPackageAugmenter) {
        this(jdkDetector, resolveService, frameworkPackageAugmenter, new PackagePlanService());
    }

    public RunPackageService(
            JdkChecker jdkDetector,
            ResolveService resolveService,
            FrameworkPackageAugmenter frameworkPackageAugmenter,
            PackagePlanService packagePlanService) {
        this(jdkDetector, resolveService, frameworkPackageAugmenter, packagePlanService, BuildProvenanceSource.empty());
    }

    public RunPackageService(
            JdkChecker jdkDetector,
            ResolveService resolveService,
            FrameworkPackageAugmenter frameworkPackageAugmenter,
            PackagePlanService packagePlanService,
            BuildProvenanceSource provenanceSource) {
        this(
                new PackageService(resolveService, frameworkPackageAugmenter, packagePlanService, provenanceSource),
                new BuildService(jdkDetector, resolveService, provenanceSource),
                new ClasspathBuilder(),
                jdkDetector,
                new JavaRunner());
    }

    RunPackageService(
            PackageService packageService,
            BuildService buildService,
            ClasspathBuilder classpathBuilder,
            JdkChecker jdkDetector,
            JavaRunner javaRunner) {
        this.packageService = packageService;
        this.buildService = buildService;
        this.classpathBuilder = classpathBuilder;
        this.jdkDetector = jdkDetector;
        this.applicationLauncher = new PackageApplicationLauncher(javaRunner);
    }

    public RunPackageResult runPackage(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            List<String> arguments) {
        return runPackage(
                projectDirectory,
                config,
                cacheRoot,
                arguments,
                new VerifiedArtifactIndex());
    }

    public RunPackageResult runPackage(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            List<String> arguments,
            VerifiedArtifactIndex artifactIndex) {
        PackageLaunchPolicy.Decision launchPolicy =
                PackageLaunchPolicy.forMode(config.packageSettings().mode());
        if (launchPolicy.strategy() == PackageLaunchPolicy.Strategy.REJECT) {
            throw new RunPackageException(launchPolicy.rejection());
        }
        String mainClass = config.project().main().orElseThrow(() -> new RunPackageException(
                "No main class is configured. Add [project].main to zolt.toml to run a packaged application."));
        packageService.preparePackageToolingIfNeeded(projectDirectory, config, cacheRoot);
        BuildResultWithClasspaths buildResult = buildService.buildWithClasspaths(
                projectDirectory,
                config,
                cacheRoot,
                false,
                artifactIndex);
        PackageResult packageResult = packageService.packageJar(projectDirectory, config, buildResult, cacheRoot);
        JdkStatus jdkStatus = jdkDetector.detect(config.project().java());
        if (!jdkStatus.ok()) {
            throw new RunPackageException("JDK check failed. " + String.join(" ", jdkStatus.problems()));
        }

        ClasspathSet classpaths = classpathBuilder.build(buildResult.classpathPackages().stream()
                .filter(dependency -> dependency.scope().packagedByDefault())
                .toList());
        JavaRunResult javaRunResult = applicationLauncher.launch(
                jdkStatus.java().orElseThrow(),
                packageResult,
                classpaths.runtime().entries(),
                mainClass,
                arguments);
        return new RunPackageResult(packageResult, javaRunResult);
    }
}
