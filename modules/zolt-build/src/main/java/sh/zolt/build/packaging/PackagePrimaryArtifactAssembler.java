package sh.zolt.build.packaging;

import sh.zolt.build.BuildResult;
import sh.zolt.build.PackageException;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.build.manifest.ManifestGenerator;
import sh.zolt.build.packageevidence.PackageArchiveDigests;
import sh.zolt.build.packageplan.PackageInputSnapshot;
import sh.zolt.build.packaging.layout.ThinJarLayoutAssembler;
import sh.zolt.framework.FrameworkPackageAugmenter;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class PackagePrimaryArtifactAssembler {
    private final PackageModePackagerRegistry packagers;

    PackagePrimaryArtifactAssembler(
            ManifestGenerator manifestGenerator,
            ZoltLockfileReader lockfileReader,
            ClasspathBuilder classpathBuilder,
            FrameworkPackageAugmenter frameworkPackageAugmenter) {
        ThinJarLayoutAssembler thinJarLayoutAssembler = new ThinJarLayoutAssembler(
                manifestGenerator,
                lockfileReader,
                classpathBuilder);
        PackageArchiveModePackager archiveModePackager = new PackageArchiveModePackager(
                manifestGenerator,
                lockfileReader,
                frameworkPackageAugmenter);
        PackageArtifactPathPlanner artifactPathPlanner = new PackageArtifactPathPlanner();
        this.packagers = PackageModePackagerRegistry.create(
                thinJarLayoutAssembler,
                archiveModePackager,
                artifactPathPlanner);
    }

    PackageResult assemble(
            Path projectDirectory,
            ProjectConfig config,
            BuildResult buildResult,
            Optional<Path> cacheRoot,
            Optional<List<ResolvedClasspathPackage>> classpathPackages,
            Optional<ClasspathSet> classpaths,
            PackageInputSnapshot applicationInputs,
            PackageArchiveDigests digests) {
        return packagers.assemble(new PackageAssemblyRequest(
                projectDirectory,
                config,
                buildResult,
                cacheRoot,
                classpathPackages,
                classpaths,
                applicationInputs,
                digests));
    }
}

final class PackageModePackagerRegistry {
    private final Map<PackageMode, PackageModePackager> packagers;

    private PackageModePackagerRegistry(Map<PackageMode, PackageModePackager> packagers) {
        EnumMap<PackageMode, PackageModePackager> copied = new EnumMap<>(PackageMode.class);
        packagers.forEach((mode, packager) -> copied.put(
                Objects.requireNonNull(mode, "mode"),
                Objects.requireNonNull(packager, "packager")));
        this.packagers = Map.copyOf(copied);
    }

    static PackageModePackagerRegistry create(
            ThinJarLayoutAssembler thinJarLayoutAssembler,
            PackageArchiveModePackager archiveModePackager,
            PackageArtifactPathPlanner artifactPathPlanner) {
        EnumMap<PackageMode, PackageModePackager> packagers = new EnumMap<>(PackageMode.class);
        packagers.put(PackageMode.THIN, request -> thinJarLayoutAssembler.assemble(
                request.projectDirectory(),
                request.config(),
                request.buildResult(),
                artifactPathPlanner.jarPath(request.projectDirectory(), request.config()),
                request.cacheRoot(),
                request.classpathPackages(),
                request.classpaths(),
                request.applicationInputs(),
                request.digests()));
        packagers.put(PackageMode.SPRING_BOOT, request -> archiveModePackager.packageSpringBootJar(
                request.projectDirectory(),
                request.config(),
                request.buildResult(),
                artifactPathPlanner.jarPath(request.projectDirectory(), request.config()),
                requiredCacheRoot(
                        request.cacheRoot(),
                        "Spring Boot package mode requires dependency jar access from zolt.lock. Persist `[package].mode = \"spring-boot\"`, run `zolt resolve`, then run `zolt package`."),
                request.classpathPackages()));
        packagers.put(PackageMode.WAR, request -> archiveModePackager.packageWar(
                request.projectDirectory(),
                request.config(),
                request.buildResult(),
                artifactPathPlanner.archivePath(request.projectDirectory(), request.config(), "war"),
                optionalCacheRoot(
                        request.cacheRoot(),
                        request.classpathPackages(),
                        "WAR package mode requires dependency jar access from zolt.lock. Use single-project `zolt package --mode war` for now; workspace WAR packaging is not wired yet."),
                request.classpathPackages(),
                request.applicationInputs(),
                request.digests()));
        packagers.put(PackageMode.SPRING_BOOT_WAR, request -> archiveModePackager.packageSpringBootWar(
                request.projectDirectory(),
                request.config(),
                request.buildResult(),
                artifactPathPlanner.archivePath(request.projectDirectory(), request.config(), "war"),
                requiredCacheRoot(
                        request.cacheRoot(),
                        "Spring Boot WAR package mode requires dependency jar access from zolt.lock. Persist `[package].mode = \"spring-boot-war\"`, run `zolt resolve`, then run `zolt package`."),
                request.classpathPackages()));
        packagers.put(PackageMode.QUARKUS, request -> archiveModePackager.packageFrameworkJar(
                request.projectDirectory(),
                request.config(),
                request.buildResult(),
                PackageMode.QUARKUS,
                requiredCacheRoot(
                        request.cacheRoot(),
                        "Framework package mode `quarkus` requires dependency jar access from zolt.lock. Use single-project `zolt package --mode quarkus` for now; workspace framework packaging is not wired yet.")));
        packagers.put(PackageMode.UBER, request -> archiveModePackager.packageUberJar(
                request.projectDirectory(),
                request.config(),
                request.buildResult(),
                artifactPathPlanner.jarPath(request.projectDirectory(), request.config()),
                optionalCacheRoot(
                        request.cacheRoot(),
                        request.classpathPackages(),
                        "Uber package mode requires dependency jar access from zolt.lock. Use single-project `zolt package --mode uber-jar` for now; workspace uber packaging is not wired yet."),
                request.classpathPackages(),
                request.applicationInputs(),
                request.digests()));
        return new PackageModePackagerRegistry(packagers);
    }

    static PackageModePackagerRegistry of(Map<PackageMode, PackageModePackager> packagers) {
        return new PackageModePackagerRegistry(packagers);
    }

    PackageResult assemble(PackageAssemblyRequest request) {
        PackageMode mode = request.config().packageSettings().mode();
        PackageModePackager packager = packagers.get(mode);
        if (packager == null) {
            throw new PackageException(
                    "No primary artifact packager is registered for package mode `"
                            + mode.configValue()
                            + "`.");
        }
        return packager.assemble(request);
    }

    List<PackageMode> supportedModes() {
        return Arrays.stream(PackageMode.values())
                .filter(packagers::containsKey)
                .toList();
    }

    private static Path requiredCacheRoot(Optional<Path> cacheRoot, String message) {
        return cacheRoot.orElseThrow(() -> new PackageException(message));
    }

    private static Path optionalCacheRoot(
            Optional<Path> cacheRoot,
            Optional<List<ResolvedClasspathPackage>> classpathPackages,
            String message) {
        if (classpathPackages.isPresent()) {
            return cacheRoot.orElse(Path.of(""));
        }
        return requiredCacheRoot(cacheRoot, message);
    }
}

@FunctionalInterface
interface PackageModePackager {
    PackageResult assemble(PackageAssemblyRequest request);
}

record PackageAssemblyRequest(
        Path projectDirectory,
        ProjectConfig config,
        BuildResult buildResult,
        Optional<Path> cacheRoot,
        Optional<List<ResolvedClasspathPackage>> classpathPackages,
        Optional<ClasspathSet> classpaths,
        PackageInputSnapshot applicationInputs,
        PackageArchiveDigests digests) {
    PackageAssemblyRequest {
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(buildResult, "buildResult");
        Objects.requireNonNull(applicationInputs, "applicationInputs");
        Objects.requireNonNull(digests, "digests");
        cacheRoot = cacheRoot == null ? Optional.empty() : cacheRoot;
        classpathPackages = classpathPackages == null ? Optional.empty() : classpathPackages;
        classpaths = classpaths == null ? Optional.empty() : classpaths;
    }
}
