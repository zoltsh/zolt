package sh.zolt.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.resolve.ResolveOptions;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.resolve.ResolveService;

/** Resolves the lock when needed and projects its verified packages for one build request. */
final class BuildClasspathResolver {
    private final ResolveService resolveService;
    private final ZoltLockfileReader lockfileReader;

    BuildClasspathResolver(ResolveService resolveService, ZoltLockfileReader lockfileReader) {
        this.resolveService = resolveService;
        this.lockfileReader = lockfileReader;
    }

    Result resolve(BuildRequest request) {
        Path lockfilePath = request.projectDirectory().resolve("zolt.lock");
        Optional<ResolveResult> resolveResult = Optional.empty();
        if (!Files.isRegularFile(lockfilePath) || generatedToolingMissing(request)) {
            resolveResult = Optional.of(resolveService.resolve(
                    request.projectDirectory(),
                    request.config(),
                    request.cacheRoot(),
                    false,
                    ResolveOptions.offline(request.offline()).withRetryCommand("zolt build")));
            request.artifactIndex().invalidateAll();
        }

        ZoltLockfile lockfile = lockfileReader.read(lockfilePath);
        List<ResolvedClasspathPackage> packages = LockfileClasspathPackageConverter.classpathPackages(
                        lockfile,
                        request.cacheRoot(),
                        request.artifactIndex()).stream()
                .filter(request.packageFilter())
                .toList();
        return new Result(resolveResult, packages);
    }

    private boolean generatedToolingMissing(BuildRequest request) {
        return GeneratedSourceToolingGate.openApiToolingMissing(
                        lockfileReader, request.projectDirectory(), request.config(), request.offline())
                || GeneratedSourceToolingGate.execToolingMissing(
                        lockfileReader, request.projectDirectory(), request.config(), request.offline());
    }

    record Result(
            Optional<ResolveResult> resolveResult,
            List<ResolvedClasspathPackage> packages) {}
}
