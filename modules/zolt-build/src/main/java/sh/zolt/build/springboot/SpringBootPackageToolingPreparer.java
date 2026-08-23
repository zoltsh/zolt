package sh.zolt.build.springboot;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.lockfile.SpringBootLoaderArtifact;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveService;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SpringBootPackageToolingPreparer {
    private final Resolver resolver;
    private final ZoltLockfileReader lockfileReader;

    public SpringBootPackageToolingPreparer(ResolveService resolveService, ZoltLockfileReader lockfileReader) {
        this(resolveService::resolve, lockfileReader);
    }

    SpringBootPackageToolingPreparer(Resolver resolver, ZoltLockfileReader lockfileReader) {
        this.resolver = resolver;
        this.lockfileReader = lockfileReader;
    }

    /**
     * Design §4.5: the caller names the authoritative lockfile. Deriving it from the project directory
     * would consult a member-local {@code zolt.lock} to decide whether Spring Boot loader tooling is
     * already locked.
     */
    public void prepareIfNeeded(
            Path projectDirectory, Path lockfilePath, ProjectConfig config, Path cacheRoot) {
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        if (!isSpringBootArchive(config.packageSettings().mode())) {
            return;
        }
        if (!Files.isRegularFile(lockfilePath)) {
            return;
        }
        ZoltLockfile lockfile = lockfileReader.read(lockfilePath);
        if (shouldResolveTooling(lockfile, config)) {
            resolver.resolve(projectRoot, config, cacheRoot);
        }
    }

    boolean shouldResolveTooling(ZoltLockfile lockfile, ProjectConfig config) {
        return !containsRuntimeSpringBootLoader(lockfile) && canResolveSpringBootLoader(config);
    }

    private static boolean containsRuntimeSpringBootLoader(ZoltLockfile lockfile) {
        return lockfile.packages().stream()
                .anyMatch(lockPackage -> SpringBootLoaderArtifact.isDefaultLoader(lockPackage)
                        && lockPackage.scope().entersMainRuntimeClasspath());
    }

    private static boolean canResolveSpringBootLoader(ProjectConfig config) {
        return !config.platforms().isEmpty()
                || config.dependencies().containsKey(SpringBootLoaderSupport.SPRING_BOOT_LOADER_PACKAGE.toString())
                || config.apiDependencies().containsKey(SpringBootLoaderSupport.SPRING_BOOT_LOADER_PACKAGE.toString());
    }

    private static boolean isSpringBootArchive(PackageMode mode) {
        return mode == PackageMode.SPRING_BOOT || mode == PackageMode.SPRING_BOOT_WAR;
    }

    @FunctionalInterface
    interface Resolver {
        void resolve(Path projectRoot, ProjectConfig config, Path cacheRoot);
    }
}
