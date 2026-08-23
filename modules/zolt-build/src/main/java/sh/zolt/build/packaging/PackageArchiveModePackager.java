package sh.zolt.build.packaging;

import sh.zolt.build.BuildResult;
import sh.zolt.build.PackageException;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.build.manifest.ManifestGenerator;
import sh.zolt.build.packaging.layout.QuarkusFastJarLayoutAssembler;
import sh.zolt.build.packaging.layout.UberJarLayoutAssembler;
import sh.zolt.build.packaging.layout.WarLayoutAssembler;
import sh.zolt.build.packageauthority.ProvidedPackagingOverrides;
import sh.zolt.build.packageevidence.PackageArchiveDigests;
import sh.zolt.build.packageplan.PackageInputSnapshot;
import sh.zolt.build.springboot.SpringBootJarLayoutAssembler;
import sh.zolt.build.springboot.SpringBootWarLayoutAssembler;
import sh.zolt.framework.FrameworkPackageAugmenter;
import sh.zolt.framework.FrameworkPackageResult;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.PackageMode;
import sh.zolt.lockfile.ProjectBuildContext;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class PackageArchiveModePackager {
    private final ZoltLockfileReader lockfileReader;
    private final FrameworkPackageAugmenter frameworkPackageAugmenter;
    private final WarLayoutAssembler warLayoutAssembler;
    private final SpringBootJarLayoutAssembler springBootJarLayoutAssembler;
    private final SpringBootWarLayoutAssembler springBootWarLayoutAssembler;
    private final QuarkusFastJarLayoutAssembler quarkusFastJarLayoutAssembler;
    private final UberJarLayoutAssembler uberJarLayoutAssembler;
    private final PackageRuntimeJarSelector runtimeJarSelector;
    private final PackageRuntimeJarMaterializer runtimeJarMaterializer;

    PackageArchiveModePackager(
            ManifestGenerator manifestGenerator,
            ZoltLockfileReader lockfileReader,
            FrameworkPackageAugmenter frameworkPackageAugmenter) {
        this.lockfileReader = lockfileReader;
        this.frameworkPackageAugmenter = frameworkPackageAugmenter;
        this.warLayoutAssembler = new WarLayoutAssembler(manifestGenerator);
        this.springBootJarLayoutAssembler = new SpringBootJarLayoutAssembler();
        this.springBootWarLayoutAssembler = new SpringBootWarLayoutAssembler();
        this.quarkusFastJarLayoutAssembler = new QuarkusFastJarLayoutAssembler();
        this.uberJarLayoutAssembler = new UberJarLayoutAssembler(manifestGenerator);
        this.runtimeJarSelector = new PackageRuntimeJarSelector();
        this.runtimeJarMaterializer = new PackageRuntimeJarMaterializer();
    }

    PackageResult packageSpringBootJar(
            Path projectDirectory,
            Path lockfilePath,
            ProjectConfig config,
            BuildResult buildResult,
            Path jarPath,
            Path cacheRoot,
            Optional<List<ResolvedClasspathPackage>> classpathPackages) {
        String startClass = config.project().main().orElseThrow(() -> new PackageException(
                "Spring Boot package mode requires [project].main in zolt.toml. Add the application main class and retry."));
        Path outputDirectory = requireOutputDirectory(buildResult);
        List<PackageRuntimeJar> runtimeJars = classpathPackages
                .map(runtimeJarSelector::runtimeJars)
                .orElseGet(() -> runtimeJarSelector.runtimeJars(
                        lockfileReader.read(lockfilePath),
                        cacheRoot));
        PackageRuntimeJarMaterializer.Result inputs =
                runtimeJarMaterializer.materialize(projectDirectory, config, runtimeJars);
        return springBootJarLayoutAssembler
                .assemble(startClass, buildResult, outputDirectory, jarPath, inputs.runtimeJars())
                .withMaterializedInputs(inputs.materializedInputs());
    }

    PackageResult packageWar(
            Path projectDirectory,
            Path lockfilePath,
            ProjectConfig config,
            BuildResult buildResult,
            Path warPath,
            Path cacheRoot,
            Optional<List<ResolvedClasspathPackage>> classpathPackages,
            PackageInputSnapshot applicationInputs,
            PackageArchiveDigests digests) {
        Path outputDirectory = requireOutputDirectory(buildResult);
        List<ResolvedClasspathPackage> resolvedPackages = classpathPackages
                .orElseGet(() -> runtimeJarSelector.allClasspathPackages(
                        lockfileReader.read(lockfilePath),
                        cacheRoot));
        ProvidedPackagingOverrides providedOverrides =
                ProvidedPackagingOverrides.fromConfigAndClasspathPackages(
                        config,
                        resolvedPackages);
        List<PackageRuntimeJar> runtimeJars =
                runtimeJarSelector.runtimeJarsWithoutProvidedDuplicates(
                        resolvedPackages,
                        PackageMode.WAR,
                        providedOverrides);
        PackageRuntimeJarMaterializer.Result inputs =
                runtimeJarMaterializer.materialize(projectDirectory, config, runtimeJars);
        return warLayoutAssembler
                .assemble(
                        projectDirectory,
                        config,
                        buildResult,
                        outputDirectory,
                        warPath,
                        inputs.runtimeJars(),
                        applicationInputs,
                        digests)
                .withMaterializedInputs(inputs.materializedInputs());
    }

    PackageResult packageUberJar(
            Path projectDirectory,
            Path lockfilePath,
            ProjectConfig config,
            BuildResult buildResult,
            Path jarPath,
            Path cacheRoot,
            Optional<List<ResolvedClasspathPackage>> classpathPackages,
            PackageInputSnapshot applicationInputs,
            PackageArchiveDigests digests) {
        Path outputDirectory = requireOutputDirectory(buildResult);
        List<PackageRuntimeJar> runtimeJars = classpathPackages
                .map(runtimeJarSelector::runtimeJars)
                .orElseGet(() -> runtimeJarSelector.runtimeJars(
                        lockfileReader.read(lockfilePath),
                        cacheRoot));
        return uberJarLayoutAssembler.assemble(
                projectDirectory,
                config,
                buildResult,
                outputDirectory,
                jarPath,
                runtimeJars,
                applicationInputs,
                digests);
    }

    PackageResult packageSpringBootWar(
            Path projectDirectory,
            Path lockfilePath,
            ProjectConfig config,
            BuildResult buildResult,
            Path warPath,
            Path cacheRoot,
            Optional<List<ResolvedClasspathPackage>> classpathPackages) {
        String startClass = config.project().main().orElseThrow(() -> new PackageException(
                "Spring Boot WAR package mode requires [project].main in zolt.toml. Add the application main class and retry."));
        Path outputDirectory = requireOutputDirectory(buildResult);
        List<ResolvedClasspathPackage> resolvedPackages = classpathPackages
                .orElseGet(() -> runtimeJarSelector.allClasspathPackages(
                        lockfileReader.read(lockfilePath),
                        cacheRoot));
        ProvidedPackagingOverrides providedOverrides =
                ProvidedPackagingOverrides.fromConfigAndClasspathPackages(
                        config,
                        resolvedPackages);
        List<PackageRuntimeJar> providedJars =
                runtimeJarSelector.providedJars(
                        resolvedPackages,
                        PackageMode.SPRING_BOOT_WAR,
                        providedOverrides);
        List<PackageRuntimeJar> runtimeJars =
                runtimeJarSelector.runtimeJarsWithoutProvidedDuplicates(
                        resolvedPackages,
                        PackageMode.SPRING_BOOT_WAR,
                        providedOverrides);
        PackageRuntimeJarMaterializer.Result runtimeInputs =
                runtimeJarMaterializer.materialize(projectDirectory, config, runtimeJars);
        PackageRuntimeJarMaterializer.Result providedInputs =
                runtimeJarMaterializer.materialize(projectDirectory, config, providedJars);
        List<PackageMaterializedInput> materializedInputs = new java.util.ArrayList<>();
        materializedInputs.addAll(runtimeInputs.materializedInputs());
        materializedInputs.addAll(providedInputs.materializedInputs());
        return springBootWarLayoutAssembler.assemble(
                startClass,
                buildResult,
                outputDirectory,
                warPath,
                runtimeInputs.runtimeJars(),
                providedInputs.runtimeJars())
                .withMaterializedInputs(materializedInputs);
    }

    PackageResult packageFrameworkJar(
            ProjectBuildContext context,
            ProjectConfig config,
            BuildResult buildResult,
            PackageMode mode,
            Path cacheRoot) {
        // Design §4.5: the adapter plans against the authoritative lock the context names, so a member
        // packaged through the workspace never has its own directory searched for a zolt.lock.
        Optional<FrameworkPackageResult> result = frameworkPackageAugmenter.augmentIfEnabled(
                context,
                config,
                cacheRoot);
        FrameworkPackageResult packageResult = result.orElseThrow(() -> new PackageException(
                frameworkPackageAugmenter.missingPackageResultMessage(mode)));
        return quarkusFastJarLayoutAssembler.assemble(
                buildResult,
                mode,
                packageResult,
                frameworkPackageAugmenter.missingRunnerJarMessage(mode, packageResult.runnerJar()),
                frameworkPackageAugmenter.inspectPackageDirectoryMessage(mode, packageResult.packageDirectory()));
    }

    private static Path requireOutputDirectory(BuildResult buildResult) {
        Path outputDirectory = buildResult.outputDirectory();
        if (!Files.isDirectory(outputDirectory)) {
            throw new PackageException(
                    "Build output directory does not exist at "
                            + outputDirectory
                            + ". Run zolt build and check [build.output].main in zolt.toml.");
        }
        return outputDirectory;
    }
}
