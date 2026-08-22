package sh.zolt.toolchain;

import sh.zolt.lockfile.ProjectLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads and resolves the optional {@code [toolchain.java.test]} runtime toolchain through the SAME
 * managed-toolchain machinery as the main toolchain (lock lookup + install probe + policy-driven
 * ambient fallback in {@link JavaToolchainStatusService}). Returns empty when a project declares no
 * test runtime toolchain, leaving test execution on the build toolchain unchanged.
 */
public final class TestRuntimeToolchainResolver {
    public static final String SOURCE = "[toolchain.java.test]";

    private final ToolchainConfigReader configReader;
    private final JavaToolchainStatusService statusService;

    public TestRuntimeToolchainResolver() {
        this(new ToolchainConfigReader(), new JavaToolchainStatusService());
    }

    TestRuntimeToolchainResolver(ToolchainConfigReader configReader, JavaToolchainStatusService statusService) {
        this.configReader = configReader;
        this.statusService = statusService;
    }

    public Optional<TestRuntimeToolchain> resolve(
            Path projectRoot,
            Path lockRoot,
            ProjectConfig config,
            HostPlatform platform,
            ToolchainStore store) {
        Optional<JavaToolchainRequest> request = configReader.readJavaTest(projectRoot.resolve("zolt.toml"));
        if (request.isEmpty()) {
            return Optional.empty();
        }
        HostPlatform effectivePlatform = platform == null ? HostPlatform.current() : platform;
        ToolchainStore effectiveStore = store == null ? ToolchainStore.defaults() : store;
        JavaToolchainStatus status = statusService.status(
                request.orElseThrow(),
                SOURCE,
                ProjectLockfile.in(lockRoot),
                effectivePlatform,
                effectiveStore);
        return Optional.of(new TestRuntimeToolchain(request.orElseThrow(), status, config.project().java()));
    }

    public TestRuntimeToolchain resolveCaptured(
            JavaToolchainRequest request,
            Optional<LockedJavaToolchain> locked,
            ProjectConfig config,
            HostPlatform platform,
            ToolchainStore store) {
        HostPlatform effectivePlatform =
                platform == null ? HostPlatform.current() : platform;
        ToolchainStore effectiveStore =
                store == null ? ToolchainStore.defaults() : store;
        JavaToolchainStatus status = statusService.status(
                request,
                SOURCE,
                true,
                locked,
                effectivePlatform,
                effectiveStore);
        return new TestRuntimeToolchain(
                request,
                status,
                config.project().java());
    }
}
