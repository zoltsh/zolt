package sh.zolt.build.packaging;

import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildResultWithClasspaths;
import sh.zolt.build.BuildService;
import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.build.packageevidence.PackageArchiveDigests;
import sh.zolt.build.packageevidence.PackageEvidenceManifestWriter;
import sh.zolt.build.packageplan.PackageOutputFingerprintIndex;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.build.manifest.ManifestGenerator;
import sh.zolt.framework.FrameworkPackageAugmenter;
import sh.zolt.lockfile.ProjectBuildContext;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.provenance.BuildProvenanceSource;
import sh.zolt.resolve.ResolveService;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class PackageService {
    private final BuildService buildService;
    private final sh.zolt.doctor.JdkChecker jdkChecker;
    private final PackageAssemblyServices assembly;

    public PackageService() {
        this(FrameworkPackageAugmenter.none());
    }

    public PackageService(FrameworkPackageAugmenter frameworkPackageAugmenter) {
        this(new ResolveService(), frameworkPackageAugmenter);
    }

    public PackageService(BuildProvenanceSource provenanceSource) {
        this(new ResolveService(), FrameworkPackageAugmenter.none(), new PackagePlanService(), provenanceSource);
    }

    public PackageService(ResolveService resolveService, FrameworkPackageAugmenter frameworkPackageAugmenter) {
        this(resolveService, frameworkPackageAugmenter, new PackagePlanService());
    }

    public PackageService(
            ResolveService resolveService,
            FrameworkPackageAugmenter frameworkPackageAugmenter,
            PackagePlanService packagePlanService) {
        this(resolveService, frameworkPackageAugmenter, packagePlanService, BuildProvenanceSource.empty());
    }

    public PackageService(
            ResolveService resolveService,
            FrameworkPackageAugmenter frameworkPackageAugmenter,
            PackagePlanService packagePlanService,
            BuildProvenanceSource provenanceSource) {
        this(
                new BuildService(resolveService, provenanceSource),
                resolveService,
                new ManifestGenerator(provenanceSource),
                new ZoltLockfileReader(),
                new ClasspathBuilder(),
                frameworkPackageAugmenter,
                packagePlanService,
                new PackageEvidenceManifestWriter(provenanceSource));
    }

    PackageService(
            BuildService buildService,
            ResolveService resolveService,
            ManifestGenerator manifestGenerator,
            ZoltLockfileReader lockfileReader,
            ClasspathBuilder classpathBuilder,
            FrameworkPackageAugmenter frameworkPackageAugmenter) {
        this(
                buildService,
                resolveService,
                manifestGenerator,
                lockfileReader,
                classpathBuilder,
                frameworkPackageAugmenter,
                new PackagePlanService());
    }

    PackageService(
            BuildService buildService,
            ResolveService resolveService,
            ManifestGenerator manifestGenerator,
            ZoltLockfileReader lockfileReader,
            ClasspathBuilder classpathBuilder,
            FrameworkPackageAugmenter frameworkPackageAugmenter,
            PackagePlanService packagePlanService) {
        this(
                buildService,
                resolveService,
                manifestGenerator,
                lockfileReader,
                classpathBuilder,
                frameworkPackageAugmenter,
                packagePlanService,
                new PackageEvidenceManifestWriter());
    }

    PackageService(
            BuildService buildService,
            ResolveService resolveService,
            ManifestGenerator manifestGenerator,
            ZoltLockfileReader lockfileReader,
            ClasspathBuilder classpathBuilder,
            FrameworkPackageAugmenter frameworkPackageAugmenter,
            PackagePlanService packagePlanService,
            PackageEvidenceManifestWriter evidenceManifestWriter) {
        this.buildService = buildService;
        this.jdkChecker = new sh.zolt.doctor.JdkDetector();
        this.assembly = PackageAssemblyServices.create(resolveService, manifestGenerator, lockfileReader,
                classpathBuilder, frameworkPackageAugmenter, packagePlanService, evidenceManifestWriter);
    }

    private PackageService(PackageService source, sh.zolt.doctor.JdkChecker jdkChecker) {
        this.jdkChecker = java.util.Objects.requireNonNull(jdkChecker, "jdkChecker");
        this.buildService = source.buildService.withJdkChecker(jdkChecker);
        this.assembly = source.assembly;
    }

    public PackageService withJdkChecker(sh.zolt.doctor.JdkChecker jdkChecker) {
        return new PackageService(this, jdkChecker);
    }

    public void preflightSupplementalTools(ProjectConfig config) {
        PackageJavadocTool.preflight(config, jdkChecker);
    }

    public PackageResult packageJar(Path projectDirectory, ProjectConfig config, Path cacheRoot) {
        return packageJar(projectDirectory, config, cacheRoot, new VerifiedArtifactIndex());
    }

    /** Packages with the command's shared artifact verification index. */
    public PackageResult packageJar(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            VerifiedArtifactIndex artifactIndex) {
        return packageJar(projectDirectory, config, config, cacheRoot, artifactIndex);
    }

    /** Builds with declared semantics, then packages separately for native JVM inputs without hiding AOT configuration. */
    public PackageResult packageJar(
            Path projectDirectory,
            ProjectConfig buildConfig,
            ProjectConfig packageConfig,
            Path cacheRoot,
            VerifiedArtifactIndex artifactIndex) {
        try (sh.zolt.build.ProjectMutationLock ignored =
                sh.zolt.build.ProjectMutationLock.acquire(projectDirectory)) {
            Path projectRoot = projectRoot(projectDirectory);
            PackageMode mode = packageConfig.packageSettings().mode();
            PackageModeValidator.ensureSupported(mode);
            preflightSupplementalTools(packageConfig);
            preparePackageToolingIfNeeded(projectRoot, packageConfig, cacheRoot);
            BuildResultWithClasspaths buildResult = buildService.buildWithClasspaths(
                    projectRoot,
                    buildConfig,
                    cacheRoot,
                    false,
                    artifactIndex);
            return packageJar(projectRoot, packageConfig, buildResult, cacheRoot);
        }
    }

    /** Standalone lane: the project directory is the lock root, so it may declare itself one. */
    public void preparePackageToolingIfNeeded(Path projectDirectory, ProjectConfig config, Path cacheRoot) {
        try (sh.zolt.build.ProjectMutationLock ignored =
                sh.zolt.build.ProjectMutationLock.acquire(projectDirectory)) {
            ProjectBuildContext context = ProjectBuildContext.standalone(projectDirectory);
            assembly.tooling().prepareIfNeeded(context.projectRoot(), context.lockfilePath(), config, cacheRoot);
        }
    }

    public PackageResult packageJar(
            Path projectDirectory,
            ProjectConfig config,
            BuildResult buildResult,
            Path cacheRoot) {
        PackageMode mode = config.packageSettings().mode();
        PackageModeValidator.ensureSupported(mode);
        return packageJar(
                ProjectBuildContext.standalone(projectDirectory),
                config,
                buildResult,
                Optional.of(cacheRoot),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new PackageOutputFingerprintIndex());
    }

    public PackageResult packageJar(
            Path projectDirectory,
            ProjectConfig config,
            BuildResultWithClasspaths buildResult,
            Path cacheRoot) {
        PackageMode mode = config.packageSettings().mode();
        PackageModeValidator.ensureSupported(mode);
        return packageJar(
                ProjectBuildContext.standalone(projectDirectory),
                config,
                buildResult.buildResult(),
                Optional.of(cacheRoot),
                Optional.of(buildResult.classpathPackages()),
                Optional.empty(),
                Optional.empty(),
                new PackageOutputFingerprintIndex());
    }

    public PackageResult packageJar(
            Path projectDirectory,
            ProjectConfig config,
            BuildResult buildResult) {
        PackageMode mode = config.packageSettings().mode();
        PackageModeValidator.ensureSupported(mode);
        return packageJar(
                ProjectBuildContext.standalone(projectDirectory),
                config,
                buildResult,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new PackageOutputFingerprintIndex());
    }

    public PackageResult packageJar(
            Path projectDirectory,
            ProjectConfig config,
            BuildResult buildResult,
            ClasspathSet classpaths) {
        PackageMode mode = config.packageSettings().mode();
        PackageModeValidator.ensureSupported(mode);
        return packageJar(
                ProjectBuildContext.standalone(projectDirectory),
                config,
                buildResult,
                Optional.empty(),
                Optional.empty(),
                Optional.of(classpaths),
                Optional.empty(),
                new PackageOutputFingerprintIndex());
    }

    public PackageResult packageJar(
            Path projectDirectory,
            ProjectConfig config,
            BuildResult buildResult,
            ClasspathSet classpaths,
            List<ResolvedClasspathPackage> classpathPackages) {
        PackageMode mode = config.packageSettings().mode();
        PackageModeValidator.ensureSupported(mode);
        return packageJar(
                ProjectBuildContext.standalone(projectDirectory),
                config,
                buildResult,
                Optional.empty(),
                Optional.of(classpathPackages),
                Optional.of(classpaths),
                Optional.empty(),
                new PackageOutputFingerprintIndex());
    }

    /** Packages a workspace member using its authoritative lockfile and the command's shared input index. */
    public PackageResult packageJar(
            ProjectBuildContext context,
            ProjectConfig config,
            BuildResult buildResult,
            Optional<Path> cacheRoot,
            ClasspathSet classpaths,
            List<ResolvedClasspathPackage> classpathPackages,
            PackagePlan packagePlan,
            PackageOutputFingerprintIndex inputs) {
        PackageMode mode = config.packageSettings().mode();
        PackageModeValidator.ensureSupported(mode);
        return packageJar(
                context,
                config,
                buildResult,
                cacheRoot,
                Optional.of(classpathPackages),
                Optional.of(classpaths),
                Optional.of(packagePlan),
                inputs);
    }

    private static Path projectRoot(Path projectDirectory) {
        return projectDirectory.toAbsolutePath().normalize();
    }

    private PackageResult packageJar(
            ProjectBuildContext context,
            ProjectConfig config,
            BuildResult buildResult,
            Optional<Path> cacheRoot,
            Optional<List<ResolvedClasspathPackage>> classpathPackages,
            Optional<ClasspathSet> classpaths,
            Optional<PackagePlan> suppliedPlan,
            PackageOutputFingerprintIndex inputs) {
        try (sh.zolt.build.ProjectMutationLock ignored =
                sh.zolt.build.ProjectMutationLock.acquire(context.projectRoot())) {
            Path projectDirectory = context.projectRoot();
            PackageJavadocPolicy.requireSupported(projectDirectory, config);
            Optional<Path> javadoc = PackageJavadocTool.preflight(config, jdkChecker);
            assembly.tests().requireCurrent(
                    context, config, buildResult, cacheRoot, classpathPackages, classpaths);
            PackagePlan plan = suppliedPlan.orElseGet(() ->
                    assembly.plans().plan(context, config, cacheRoot, inputs));
            try {
                Optional<PackageResult> reused =
                        assembly.reuse().reuse(projectDirectory, config, buildResult, plan);
                if (reused.isPresent()) {
                    return reused.orElseThrow();
                }
                PackageArchiveDigests digests = new PackageArchiveDigests();
                PackageResult result = assembly.primary().assemble(
                        context,
                        config,
                        buildResult,
                        cacheRoot,
                        classpathPackages,
                        classpaths,
                        inputs.snapshot(plan.applicationOutput()),
                        digests);
                List<PackageArtifact> artifacts = assembly.supplemental().assemble(
                        projectDirectory,
                        config,
                        buildResult,
                        classpathPackages,
                        classpaths,
                        javadoc);
                Path evidenceManifest = assembly.evidence().write(
                        projectDirectory, config, plan, result, artifacts, digests);
                return result.withArtifactsAndEvidence(artifacts, Optional.of(evidenceManifest));
            } finally {
                inputs.releaseBytes(plan.applicationOutput());
            }
        }
    }

}
