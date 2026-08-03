package sh.zolt.build.coverage;

import sh.zolt.build.CoverageException;
import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class CoverageToolingLock {
    private static final PackageId AGENT =
            new PackageId("org.jacoco", "org.jacoco.agent");
    private static final PackageId CLI =
            new PackageId("org.jacoco", "org.jacoco.cli");

    private final ZoltLockfileReader lockfiles;

    CoverageToolingLock(ZoltLockfileReader lockfiles) {
        this.lockfiles = lockfiles;
    }

    CoverageTooling read(Path lockfileDirectory, Path cacheRoot) {
        Path lockfile = lockfileDirectory
                .toAbsolutePath()
                .normalize()
                .resolve("zolt.lock");
        return read(lockfiles.read(lockfile), cacheRoot);
    }

    CoverageTooling read(ZoltLockfile lockfile, Path cacheRoot) {
        List<ResolvedClasspathPackage> packages =
                coveragePackages(lockfile, cacheRoot);
        Path agentJar = artifact(packages, AGENT)
                .orElseThrow(() -> missing(
                        "org.jacoco:org.jacoco.agent"));
        if (!agentJar.getFileName().toString().contains("-runtime")) {
            throw new CoverageException(
                    "Coverage requires locked Jacoco runtime agent artifact `org.jacoco:org.jacoco.agent:runtime`. "
                            + "Run `zolt resolve` to refresh coverage tooling.");
        }
        List<Path> cliClasspath = packages.stream()
                .map(dependency -> dependency.resolvedPackage()
                        .jarPath()
                        .toAbsolutePath()
                        .normalize())
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        if (artifact(packages, CLI).isEmpty()) {
            throw missing("org.jacoco:org.jacoco.cli");
        }
        return new CoverageTooling(agentJar, cliClasspath);
    }

    private static List<ResolvedClasspathPackage> coveragePackages(
            ZoltLockfile lockfile,
            Path cacheRoot) {
        List<ResolvedClasspathPackage> packages =
                LockfileClasspathPackageConverter
                        .classpathPackages(lockfile, cacheRoot)
                        .stream()
                        .filter(dependency -> dependency.scope()
                                == DependencyScope.TOOL_COVERAGE)
                        .toList();
        if (packages.isEmpty()) {
            throw new CoverageException(
                    "Coverage requires locked tooling in scope `tool-coverage`. Run `zolt resolve` to refresh Jacoco tooling, then run `zolt coverage` again.");
        }
        return packages;
    }

    private static Optional<Path> artifact(
            List<ResolvedClasspathPackage> packages,
            PackageId packageId) {
        return packages.stream()
                .filter(dependency -> dependency.resolvedPackage()
                        .packageId()
                        .equals(packageId))
                .map(dependency -> dependency.resolvedPackage().jarPath())
                .findFirst();
    }

    private static CoverageException missing(String coordinate) {
        return new CoverageException(
                "Coverage requires locked tooling artifact `"
                        + coordinate
                        + "` in scope `tool-coverage`. "
                        + "Run `zolt resolve` to refresh Jacoco tooling, then run `zolt coverage` again.");
    }
}
