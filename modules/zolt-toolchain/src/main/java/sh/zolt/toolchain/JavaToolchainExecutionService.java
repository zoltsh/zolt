package sh.zolt.toolchain;

import sh.zolt.error.ActionableException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import java.nio.file.Path;
import java.util.Optional;

public final class JavaToolchainExecutionService {
    private final JavaToolchainStatusService statusService;

    public JavaToolchainExecutionService() {
        this(new JavaToolchainStatusService());
    }

    JavaToolchainExecutionService(JavaToolchainStatusService statusService) {
        this.statusService = statusService;
    }

    /**
     * The Native Image executable for a command boundary.
     *
     * <p>Design §4.5 gives a command two roots: {@code projectRoot} owns the manifest that authors the
     * request, {@code lockRoot} owns the one authoritative {@code zolt.lock}. Both are stated, never
     * derived from each other — a standalone project passes its own directory twice. There is
     * deliberately no one-root overload: from the signature alone a caller could not tell whether the
     * directory it passed is allowed to own a lock, and for a workspace member it is not.
     */
    public Optional<Path> nativeImage(
            Path projectRoot,
            Path lockRoot,
            ProjectConfig config,
            HostPlatform platform,
            ToolchainStore store) {
        JavaToolchainStatus status = status(projectRoot, lockRoot, config, platform, store);
        if (!status.ok()) {
            if (status.projectToolchainConfigured()) {
                throw unresolvedToolchain(
                        status,
                        "Java toolchain is not ready for Native Image",
                        "Run `zolt toolchain status` for details, then `zolt toolchain sync`, or pass --native-image with an executable path.");
            }
            return Optional.empty();
        }
        Optional<Path> nativeImage = status.resolved().nativeImage();
        if (nativeImage.isPresent()) {
            return nativeImage;
        }
        if (status.projectToolchainConfigured()) {
            throw new ActionableException(
                    "Resolved Java toolchain does not provide native-image.",
                    "Add `features = [\"native-image\"]` to [toolchain.java] and run `zolt toolchain sync`, or pass --native-image with an executable path.");
        }
        return Optional.empty();
    }

    /** As {@link #environment(Path, Path, ProjectConfig, HostPlatform, ToolchainStore, String, String)}, with exec's guidance. */
    public JavaToolchainEnvironment environment(
            Path projectRoot,
            Path lockRoot,
            ProjectConfig config,
            HostPlatform platform,
            ToolchainStore store) {
        return environment(
                projectRoot,
                lockRoot,
                config,
                platform,
                store,
                "Java toolchain is not ready for exec",
                "Run `zolt toolchain status` for details, then `zolt toolchain sync`, or choose a project with a usable Java toolchain.");
    }

    public JavaToolchainEnvironment environment(
            JavaToolchainRequest request,
            String requestSource,
            Path lockfile,
            HostPlatform platform,
            ToolchainStore store,
            String unresolvedSummary,
            String unresolvedRemediation) {
        JavaToolchainStatus status = statusService.status(request, requestSource, lockfile, platform, store);
        return environment(status, unresolvedSummary, unresolvedRemediation);
    }

    public JavaToolchainEnvironment environment(
            JavaToolchainRequest request,
            String requestSource,
            boolean projectPinned,
            Optional<LockedJavaToolchain> locked,
            HostPlatform platform,
            ToolchainStore store,
            String unresolvedSummary,
            String unresolvedRemediation) {
        JavaToolchainStatus status = statusService.status(
                request,
                requestSource,
                projectPinned,
                locked,
                platform,
                store);
        return environment(status, unresolvedSummary, unresolvedRemediation);
    }

    /**
     * The Java toolchain environment for a command boundary, with caller-supplied guidance.
     *
     * <p>Both roots are stated for the same reason {@link #nativeImage(Path, Path, ProjectConfig,
     * HostPlatform, ToolchainStore)} states them: {@code projectRoot} authors the request,
     * {@code lockRoot} owns the lock, and a standalone project passes its directory twice.
     */
    public JavaToolchainEnvironment environment(
            Path projectRoot,
            Path lockRoot,
            ProjectConfig config,
            HostPlatform platform,
            ToolchainStore store,
            String unresolvedSummary,
            String unresolvedRemediation) {
        JavaToolchainStatus status = status(projectRoot, lockRoot, config, platform, store);
        return environment(status, unresolvedSummary, unresolvedRemediation);
    }

    private static JavaToolchainEnvironment environment(
            JavaToolchainStatus status,
            String unresolvedSummary,
            String unresolvedRemediation) {
        if (!status.ok()) {
            throw unresolvedToolchain(
                    status,
                    unresolvedSummary,
                    unresolvedRemediation);
        }
        Path javaHome = status.resolved().javaHome().orElseThrow(() -> new ActionableException(
                "Resolved Java toolchain does not expose JAVA_HOME.",
                "Run `zolt toolchain status` for details or pass an explicit command path."));
        Path absoluteJavaHome = javaHome.toAbsolutePath().normalize();
        return new JavaToolchainEnvironment(
                absoluteJavaHome,
                absoluteJavaHome.resolve("bin").normalize(),
                status.resolved());
    }

    private JavaToolchainStatus status(
            Path projectRoot,
            Path lockRoot,
            ProjectConfig config,
            HostPlatform platform,
            ToolchainStore store) {
        return statusService.status(projectRoot, lockRoot, config, platform, store);
    }

    private static ActionableException unresolvedToolchain(
            JavaToolchainStatus status,
            String summary,
            String remediation) {
        String problem = status.resolved().problems().stream()
                .findFirst()
                .orElse("The Java toolchain could not be resolved.");
        return new ActionableException(
                summary + ": " + problem,
                remediation);
    }
}
