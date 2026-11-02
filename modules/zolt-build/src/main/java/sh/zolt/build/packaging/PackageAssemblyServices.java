package sh.zolt.build.packaging;

import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.build.manifest.ManifestGenerator;
import sh.zolt.build.packageevidence.PackageEvidenceManifestWriter;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.build.springboot.SpringBootPackageToolingPreparer;
import sh.zolt.framework.FrameworkPackageAugmenter;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.resolve.ResolveService;

/** Assembly collaborators remain shared when a command selects a different build/documentation JDK. */
record PackageAssemblyServices(
        SpringBootPackageToolingPreparer tooling,
        PackagePlanResolver plans,
        PackageEvidenceManifestWriter evidence,
        PackagePrimaryArtifactAssembler primary,
        PackageSupplementalArtifactAssembler supplemental,
        PackageTestCompileGate tests,
        PackageReuseService reuse) {
    static PackageAssemblyServices create(
            ResolveService resolver,
            ManifestGenerator manifest,
            ZoltLockfileReader lockfile,
            ClasspathBuilder classpaths,
            FrameworkPackageAugmenter framework,
            PackagePlanService plans,
            PackageEvidenceManifestWriter evidence) {
        return new PackageAssemblyServices(
                new SpringBootPackageToolingPreparer(resolver, lockfile),
                new PackagePlanResolver(plans == null ? new PackagePlanService() : plans),
                evidence,
                new PackagePrimaryArtifactAssembler(manifest, lockfile, classpaths, framework),
                new PackageSupplementalArtifactAssembler(classpaths),
                new PackageTestCompileGate(lockfile, classpaths),
                new PackageReuseService());
    }
}
