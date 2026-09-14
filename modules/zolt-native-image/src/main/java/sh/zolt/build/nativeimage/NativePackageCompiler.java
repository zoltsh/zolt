package sh.zolt.build.nativeimage;

import sh.zolt.build.BuildResultWithClasspaths;
import sh.zolt.build.BuildService;
import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.packaging.PackageService;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.project.ProjectConfig;
import sh.zolt.provenance.BuildProvenanceSource;
import java.nio.file.Path;

/** Builds and packages the private JVM input used by Native Image. */
final class NativePackageCompiler {
    private final BuildService buildService;
    private final PackageService packageService;

    NativePackageCompiler(BuildProvenanceSource provenanceSource) {
        this(
                BuildService.withProvenance(provenanceSource),
                new PackageService(provenanceSource));
    }

    NativePackageCompiler(BuildService buildService, PackageService packageService) {
        this.buildService = buildService;
        this.packageService = packageService;
    }

    PackageResult packageJar(
            Path projectDirectory,
            ProjectConfig buildConfig,
            ProjectConfig packageConfig,
            Path cacheRoot,
            VerifiedArtifactIndex artifactIndex,
            JdkChecker jdkChecker) {
        packageService.preparePackageToolingIfNeeded(projectDirectory, packageConfig, cacheRoot);
        BuildResultWithClasspaths buildResult = buildService
                .withJdkChecker(jdkChecker)
                .buildWithClasspaths(
                        projectDirectory,
                        buildConfig,
                        cacheRoot,
                        false,
                        artifactIndex);
        return packageService.packageJar(projectDirectory, packageConfig, buildResult, cacheRoot);
    }
}
