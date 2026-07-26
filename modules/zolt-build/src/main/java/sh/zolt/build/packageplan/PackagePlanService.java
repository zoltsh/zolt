package sh.zolt.build.packageplan;

import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.build.BuildException;
import sh.zolt.build.PackageException;
import sh.zolt.build.generatedsource.GeneratedSourceProducerFingerprint;
import sh.zolt.build.generatedsource.GeneratedSourceProducerFingerprintService;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.framework.FrameworkPackagePlanRules;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPaths;
import sh.zolt.dependency.DependencyScope;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class PackagePlanService {
    private final ZoltLockfileReader lockfileReader;
    private final List<FrameworkPackagePlanRules> packagePlanRules;
    private final GeneratedSourceProducerFingerprintService
            generatedSourceFingerprintService;

    public PackagePlanService() {
        this(List.of());
    }

    public PackagePlanService(List<FrameworkPackagePlanRules> packagePlanRules) {
        this(
                new ZoltLockfileReader(),
                packagePlanRules,
                new GeneratedSourceProducerFingerprintService());
    }

    PackagePlanService(ZoltLockfileReader lockfileReader) {
        this(lockfileReader, List.of());
    }

    PackagePlanService(ZoltLockfileReader lockfileReader, List<FrameworkPackagePlanRules> packagePlanRules) {
        this(
                lockfileReader,
                packagePlanRules,
                new GeneratedSourceProducerFingerprintService());
    }

    PackagePlanService(
            ZoltLockfileReader lockfileReader,
            List<FrameworkPackagePlanRules> packagePlanRules,
            GeneratedSourceProducerFingerprintService
                    generatedSourceFingerprintService) {
        this.lockfileReader = lockfileReader;
        this.packagePlanRules = packagePlanRules == null ? List.of() : List.copyOf(packagePlanRules);
        this.generatedSourceFingerprintService =
                generatedSourceFingerprintService;
    }

    public PackagePlan plan(Path projectDirectory, ProjectConfig config) {
        Path projectRoot = projectRoot(projectDirectory);
        return plan(
                projectRoot,
                config,
                projectRoot.resolve("zolt.lock"),
                LocalArtifactCache.defaultRoot());
    }

    public PackagePlan plan(Path projectDirectory, ProjectConfig config, Path lockfilePath) {
        return plan(
                projectDirectory,
                config,
                lockfilePath,
                LocalArtifactCache.defaultRoot());
    }

    public PackagePlan plan(
            Path projectDirectory,
            ProjectConfig config,
            Path lockfilePath,
            Path cacheRoot) {
        Path projectRoot = projectRoot(projectDirectory);
        ZoltLockfile lockfile = lockfileReader.read(lockfilePath.toAbsolutePath().normalize());
        return plan(projectRoot, config, lockfile, cacheRoot);
    }

    public PackagePlan plan(
            Path projectDirectory,
            ProjectConfig config,
            ZoltLockfile lockfile) {
        return plan(
                projectDirectory,
                config,
                lockfile,
                LocalArtifactCache.defaultRoot());
    }

    public PackagePlan plan(
            Path projectDirectory,
            ProjectConfig config,
            ZoltLockfile lockfile,
            Path cacheRoot) {
        Path projectRoot = projectRoot(projectDirectory);
        PackageMode mode = config.packageSettings().mode();
        Set<String> providedArtifactVariants =
                providedArtifactVariants(lockfile);
        Optional<FrameworkPackagePlanRules> modeRules = packagePlanRules(mode);
        List<PackagePlanDependency> dependencies = PackagePlanNestedDependencies
                .canonicalize(mode == PackageMode.BOM
                        ? List.of()
                        : lockfile.packages().stream()
                        .filter(PackagePlanService::packageInput)
                        .sorted(Comparator.comparing(PackagePlanService::sortKey))
                        .map(lockPackage -> PackagePlanDependencyClassifier.dependency(
                                mode,
                                lockPackage,
                                providedArtifactVariants,
                                modeRules,
                                config))
                        .toList());
        Path archivePath = archivePath(projectRoot, config, mode, modeRules);
        Path applicationOutput = applicationOutput(projectRoot, config, mode);
        String applicationLayout = applicationLayout(mode, modeRules, config);
        Optional<Path> runtimeClasspathPath =
                runtimeClasspathPath(projectRoot, config, mode);
        List<PackagePlanOutput> outputs = PackagePlanOutputs.forConfig(
                projectRoot,
                config,
                archivePath,
                runtimeClasspathPath);
        String frameworkRulesIdentity = modeRules
                .map(FrameworkPackagePlanRules::evidenceIdentity)
                .orElse("zolt-core-package-plan-v2:" + mode.configValue());
        List<PackagePlanWorkspaceInput> workspaceInputs =
                mode == PackageMode.BOM
                        ? List.of()
                        : PackageWorkspaceInputPlanner.workspaceInputs(
                                projectRoot,
                                lockfile.packages());
        List<PackagePlanMaterializedInput> materializedInputs =
                PackageWorkspaceInputPlanner.materializedInputs(
                        projectRoot,
                        config,
                        dependencies,
                        workspaceInputs);
        List<GeneratedSourceProducerFingerprint> generatedSourceFingerprints;
        try {
            if (mode == PackageMode.BOM) {
                generatedSourceFingerprints = List.of();
            } else {
                var generatedClasspath = PackageGeneratedSourceClasspath.packages(
                        projectRoot,
                        cacheRoot,
                        lockfile);
                List<GeneratedSourceProducerFingerprint> selected =
                        new ArrayList<>(generatedSourceFingerprintService.fingerprintsMain(
                                projectRoot,
                                config,
                                generatedClasspath));
                if (outputs.stream().anyMatch(output -> "tests".equals(output.kind()))) {
                    selected.addAll(generatedSourceFingerprintService.fingerprintsTest(
                            projectRoot,
                            config,
                            generatedClasspath));
                }
                generatedSourceFingerprints = List.copyOf(selected);
            }
        } catch (BuildException exception) {
            if (exception.actionableError() != null) {
                throw new PackageException(exception.actionableError());
            }
            throw new PackageException(
                    "Could not fingerprint generated-source producer: "
                            + exception.getMessage(),
                    exception);
        }
        String buildInputFingerprint =
                mode == PackageMode.BOM
                        ? "not-applicable"
                        : PackageBuildInputFingerprint.fingerprint(
                                projectRoot,
                                config,
                                lockfile,
                                workspaceInputs,
                                generatedSourceFingerprints);
        String applicationOutputFingerprint =
                mode == PackageMode.BOM
                        ? "not-applicable"
                        : PackageInputFingerprinting.applicationOutputFingerprint(
                                applicationOutput);
        String packageLockFingerprint =
                PackageInputFingerprint.packageLockFingerprint(lockfile);
        List<PackagePlanLiveInput> supplementalInputs =
                mode == PackageMode.BOM
                        ? List.of()
                        : PackageSupplementalInputFingerprint.inputs(
                                projectRoot,
                                config,
                                buildInputFingerprint,
                                applicationOutputFingerprint,
                                packageLockFingerprint,
                                generatedSourceFingerprints);
        return new PackagePlan(
                projectRoot,
                mode,
                archivePath,
                applicationOutput,
                applicationLayout,
                runtimeClasspathPath,
                dependencies,
                PackagePlanWarnings.forPlan(mode, modeRules, dependencies),
                PackageInputFingerprint.evidence(
                        projectRoot,
                        config,
                        lockfile,
                        frameworkRulesIdentity,
                        archivePath,
                        applicationOutput,
                        applicationLayout,
                        dependencies,
                        outputs,
                        buildInputFingerprint,
                        applicationOutputFingerprint,
                        supplementalInputs,
                        workspaceInputs,
                        materializedInputs));
    }

    private static Path applicationOutput(
            Path projectRoot,
            ProjectConfig config,
            PackageMode mode) {
        if (mode == PackageMode.BOM) {
            return ProjectPaths.output(
                    projectRoot,
                    "BOM package output",
                    config.build().outputRoot() + "/publish");
        }
        return ProjectPaths.output(
                projectRoot,
                "[build].output",
                config.build().output());
    }

    private static boolean packageInput(LockPackage lockPackage) {
        return lockPackage.jar().isPresent()
                || (lockPackage.workspace().isPresent()
                        && lockPackage.workspaceOutput().isPresent());
    }

    private Optional<FrameworkPackagePlanRules> packagePlanRules(PackageMode mode) {
        return packagePlanRules.stream()
                .filter(rules -> rules.supports(mode))
                .findFirst();
    }

    private static Set<String> providedArtifactVariants(ZoltLockfile lockfile) {
        Set<String> variants = new LinkedHashSet<>();
        for (LockPackage lockPackage : lockfile.packages()) {
            if (lockPackage.scope() == DependencyScope.PROVIDED) {
                variants.add(
                        NestedArtifactIdentity.of(lockPackage).artifactVariantKey());
            }
        }
        return Set.copyOf(variants);
    }

    private static Path archivePath(
            Path projectRoot,
            ProjectConfig config,
            PackageMode mode,
            Optional<FrameworkPackagePlanRules> packagePlanRules) {
        if (packagePlanRules.isPresent()) {
            return packagePlanRules.orElseThrow().archivePath(projectRoot, config);
        }
        return switch (mode) {
            case WAR, SPRING_BOOT_WAR -> ProjectPaths.output(
                    projectRoot,
                    "package archive",
                    config.build().outputRoot() + "/" + artifactBaseName(config) + ".war");
            case QUARKUS -> ProjectPaths.output(
                    projectRoot,
                    "package archive",
                    config.build().outputRoot() + "/" + artifactBaseName(config) + ".jar");
            case BOM -> ProjectPaths.output(
                    projectRoot,
                    "BOM package artifact",
                    config.build().outputRoot() + "/publish/" + artifactBaseName(config) + ".pom");
            default -> ProjectPaths.output(
                    projectRoot,
                    "package archive",
                    config.build().outputRoot() + "/" + artifactBaseName(config) + ".jar");
        };
    }

    private static Optional<Path> runtimeClasspathPath(Path projectRoot, ProjectConfig config, PackageMode mode) {
        if (mode != PackageMode.THIN) {
            return Optional.empty();
        }
        return Optional.of(ProjectPaths.output(
                projectRoot,
                "package runtime classpath",
                config.build().outputRoot() + "/" + artifactBaseName(config) + ".runtime-classpath"));
    }

    private static String artifactBaseName(ProjectConfig config) {
        return ProjectPaths.filenameComponent("[project].name", config.project().name())
                + "-"
                + ProjectPaths.filenameComponent("[project].version", config.project().version());
    }

    private static String applicationLayout(
            PackageMode mode,
            Optional<FrameworkPackagePlanRules> packagePlanRules,
            ProjectConfig config) {
        if (packagePlanRules.isPresent()) {
            return packagePlanRules.orElseThrow().applicationLayout(config);
        }
        return switch (mode) {
            case THIN, UBER -> "archive root";
            case SPRING_BOOT -> "BOOT-INF/classes";
            case WAR, SPRING_BOOT_WAR -> "WEB-INF/classes";
            case QUARKUS -> "framework package output";
            case BOM -> "dependencyManagement POM";
        };
    }

    private static String sortKey(LockPackage lockPackage) {
        return lockPackage.packageId()
                + ":"
                + lockPackage.version()
                + ":"
                + NestedArtifactIdentity.of(lockPackage).canonicalKey()
                + ":"
                + lockPackage.scope().lockfileName();
    }

    private static Path projectRoot(Path projectDirectory) {
        return projectDirectory.toAbsolutePath().normalize();
    }
}
