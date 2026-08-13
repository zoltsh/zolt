package sh.zolt.build.packageplan;

import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.classpath.ResolvedPackage;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.nio.file.Path;
import java.util.List;

/**
 * Resolves the current local paths that generated-source producer fingerprints consume.
 */
final class PackageGeneratedSourceClasspath {
    private PackageGeneratedSourceClasspath() {
    }

    static List<ResolvedClasspathPackage> packages(
            Path projectRoot,
            Path cacheRoot,
            ZoltLockfile lockfile) {
        Path cache = cacheRoot.toAbsolutePath().normalize();
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.jar().isPresent()
                        || (lockPackage.workspace().isPresent()
                                && lockPackage.workspaceOutput().isPresent()))
                .map(lockPackage -> resolved(projectRoot, cache, lockPackage))
                .toList();
    }

    private static ResolvedClasspathPackage resolved(
            Path projectRoot,
            Path cacheRoot,
            LockPackage lockPackage) {
        Path jar = lockPackage.workspace().isPresent()
                ? PackageWorkspaceInputPlanner.sourceDirectory(
                        projectRoot,
                        lockPackage)
                : lockPackage.jarPath().orElseThrow().resolveWithin(cacheRoot);
        Path pom = lockPackage.pomPath()
                .map(path -> path.resolveWithin(cacheRoot))
                .orElse(Path.of(""));
        return new ResolvedClasspathPackage(
                new ResolvedPackage(
                        lockPackage.packageId(),
                        lockPackage.version(),
                        lockPackage.direct(),
                        pom,
                        jar,
                        NestedArtifactIdentity.of(lockPackage)),
                lockPackage.scope(),
                lockPackage.toolGroups());
    }
}
