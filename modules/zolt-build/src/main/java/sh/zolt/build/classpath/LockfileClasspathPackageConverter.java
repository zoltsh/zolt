package sh.zolt.build.classpath;

import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.classpath.ResolvedPackage;
import sh.zolt.build.lockfile.ArtifactIntegrityVerifier;
import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockPackageCachePath;
import sh.zolt.lockfile.LockPackagePathKind;
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
        return classpathPackages(lockfile, cacheRoot, new VerifiedArtifactIndex());
    }

    /**
     * Projects the lock view onto a classpath, verifying its artifacts through {@code artifactIndex}
     * so artifacts already verified for the command are not read again.
     */
    public static List<ResolvedClasspathPackage> classpathPackages(
            ZoltLockfile lockfile,
            Path cacheRoot,
            VerifiedArtifactIndex artifactIndex) {
        new ArtifactIntegrityVerifier(artifactIndex).verify(lockfile, cacheRoot);
        return toClasspathPackages(lockfile, cacheRoot);
    }

    public static List<ResolvedClasspathPackage> classpathPackages(
            ZoltLockfile lockfile,
            Path cacheRoot,
            Path workspaceRoot) {
        return classpathPackages(lockfile, cacheRoot, workspaceRoot, new VerifiedArtifactIndex());
    }

    public static List<ResolvedClasspathPackage> classpathPackages(
            ZoltLockfile lockfile,
            Path cacheRoot,
            Path workspaceRoot,
            VerifiedArtifactIndex artifactIndex) {
        new ArtifactIntegrityVerifier(artifactIndex).verify(lockfile, cacheRoot);
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.jar().isPresent()
                        || (lockPackage.workspace().isPresent() && lockPackage.workspaceOutput().isPresent()))
                .map(lockPackage -> {
                    Path classpathPath = lockPackage.workspace().isPresent()
                            ? workspaceClasspathPath(workspaceRoot, lockPackage)
                            : LockPackageCachePath.path(lockPackage, LockPackagePathKind.JAR)
                                    .orElseThrow()
                                    .resolveWithin(cacheRoot);
                    return new ResolvedClasspathPackage(
                            new ResolvedPackage(
                                    lockPackage.packageId(),
                                    lockPackage.version(),
                                    lockPackage.direct(),
                                    LockPackageCachePath.path(lockPackage, LockPackagePathKind.POM)
                                            .map(value -> value.resolveWithin(cacheRoot))
                                            .orElse(Path.of("")),
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
                                LockPackageCachePath.path(lockPackage, LockPackagePathKind.POM)
                                        .map(value -> value.resolveWithin(cacheRoot))
                                        .orElse(Path.of("")),
                                LockPackageCachePath.path(lockPackage, LockPackagePathKind.JAR)
                                        .orElseThrow()
                                        .resolveWithin(cacheRoot),
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
