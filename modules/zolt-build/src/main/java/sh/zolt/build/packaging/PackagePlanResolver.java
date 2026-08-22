package sh.zolt.build.packaging;

import sh.zolt.lockfile.ProjectLockfile;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackageOutputFingerprintIndex;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class PackagePlanResolver {
    private final PackagePlanService packagePlanService;
    private final ZoltLockfileReader lockfileReader = new ZoltLockfileReader();

    PackagePlanResolver(PackagePlanService packagePlanService) {
        this.packagePlanService = packagePlanService;
    }

    PackagePlan plan(
            Path projectDirectory,
            ProjectConfig config,
            Optional<Path> cacheRoot,
            PackageOutputFingerprintIndex inputs) {
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        Path artifacts = cacheRoot.orElseGet(LocalArtifactCache::defaultRoot);
        ZoltLockfile lockfile = Files.isRegularFile(ProjectLockfile.in(projectRoot))
                ? lockfileReader.read(ProjectLockfile.in(projectRoot))
                : new ZoltLockfile(ZoltLockfile.CURRENT_VERSION, List.of(), List.of());
        return packagePlanService.plan(projectRoot, config, lockfile, artifacts, inputs);
    }
}
