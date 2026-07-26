package sh.zolt.build.classpath;

import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.classpath.ResolvedPackage;
import sh.zolt.build.lockfile.ArtifactIntegrityVerifier;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import java.nio.file.Path;
import java.util.List;

public final class LockfileClasspathPackageConverter {
    private LockfileClasspathPackageConverter() {
    }

    public static List<ResolvedClasspathPackage> classpathPackages(ZoltLockfile lockfile) {
        return toClasspathPackages(lockfile, Path.of(""));
    }

    public static List<ResolvedClasspathPackage> classpathPackages(ZoltLockfile lockfile, Path cacheRoot) {
        new ArtifactIntegrityVerifier().verify(lockfile, cacheRoot);
        return toClasspathPackages(lockfile, cacheRoot);
    }

    public static List<ResolvedClasspathPackage> classpathPackages(
            ZoltLockfile lockfile,
            Path cacheRoot,
            Path workspaceRoot) {
        new ArtifactIntegrityVerifier().verify(lockfile, cacheRoot);
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.jar().isPresent()
                        || (lockPackage.workspace().isPresent() && lockPackage.workspaceOutput().isPresent()))
                .map(lockPackage -> {
                    Path classpathPath = lockPackage.workspace().isPresent()
                            ? workspaceClasspathPath(workspaceRoot, lockPackage)
                            : cacheRoot.resolve(lockPackage.jar().orElseThrow());
                    return new ResolvedClasspathPackage(
                            new ResolvedPackage(
                                    lockPackage.packageId(),
                                    lockPackage.version(),
                                    lockPackage.direct(),
                                    lockPackage.pom().map(value -> cacheRoot.resolve(value)).orElse(Path.of("")),
                                    classpathPath,
                                    NestedArtifactIdentity.of(lockPackage)),
                            lockPackage.scope(),
                            lockPackage.toolGroups());
                })
                .toList();
    }

    private static List<ResolvedClasspathPackage> toClasspathPackages(ZoltLockfile lockfile, Path cacheRoot) {
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.jar().isPresent())
                .map(lockPackage -> new ResolvedClasspathPackage(
                        new ResolvedPackage(
                                lockPackage.packageId(),
                                lockPackage.version(),
                                lockPackage.direct(),
                                lockPackage.pom().map(value -> cacheRoot.resolve(value)).orElse(Path.of("")),
                                cacheRoot.resolve(lockPackage.jar().orElseThrow()),
                                NestedArtifactIdentity.of(lockPackage)),
                        lockPackage.scope(),
                        lockPackage.toolGroups()))
                .toList();
    }

    private static Path workspaceClasspathPath(Path workspaceRoot, LockPackage lockPackage) {
        try {
            Path root = ProjectPaths.root(workspaceRoot);
            String workspace = lockPackage.workspace().orElseThrow();
            Path memberRoot = ProjectPaths.existingRoot(root, "workspace", workspace);
            return ProjectPaths.output(memberRoot, "workspaceOutput", lockPackage.workspaceOutput().orElseThrow());
        } catch (ProjectPathException exception) {
            throw new LockfileReadException(exception.getMessage(), exception);
        }
    }
}
