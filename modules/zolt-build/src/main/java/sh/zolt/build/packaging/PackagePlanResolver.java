package sh.zolt.build.packaging;

import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class PackagePlanResolver {
    private final PackagePlanService packagePlanService;

    PackagePlanResolver(PackagePlanService packagePlanService) {
        this.packagePlanService = packagePlanService;
    }

    PackagePlan plan(
            Path projectDirectory,
            ProjectConfig config,
            Optional<Path> cacheRoot) {
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        Path artifacts = cacheRoot.orElseGet(LocalArtifactCache::defaultRoot);
        if (Files.isRegularFile(projectRoot.resolve("zolt.lock"))) {
            return packagePlanService.plan(
                    projectRoot,
                    config,
                    projectRoot.resolve("zolt.lock"),
                    artifacts);
        }
        return packagePlanService.plan(
                projectRoot,
                config,
                new ZoltLockfile(
                        ZoltLockfile.CURRENT_VERSION,
                        List.of(),
                        List.of()),
                artifacts);
    }
}
