package sh.zolt.toolchain;

import sh.zolt.error.ActionableException;
import sh.zolt.net.NetworkTransport;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.toolchain.catalog.JavaToolchainArtifact;
import sh.zolt.toolchain.catalog.JavaToolchainCatalog;
import sh.zolt.toolchain.catalog.ResolvingJavaToolchainCatalog;
import sh.zolt.toolchain.install.JavaToolchainDownloader;
import sh.zolt.toolchain.install.JavaToolchainInstaller;
import sh.zolt.toolchain.install.ToolchainDownloadMirror;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.lock.ToolchainLockfileService;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

public final class ToolchainSyncService {
    private final ToolchainConfigReader configReader;
    private final JavaToolchainCatalog catalog;
    private final ToolchainLockfileService lockfiles;
    private final JavaToolchainInstaller installer;

    public ToolchainSyncService() {
        this(
                new ToolchainConfigReader(),
                new ResolvingJavaToolchainCatalog(NetworkTransport.fromEnvironment()),
                new ToolchainLockfileService(),
                new JavaToolchainInstaller());
    }

    /**
     * A sync service whose downloader routes JDK archives through the given proxy/CA transport and
     * mirror. Provider metadata and archives use the same transport, while mirrored downloads are
     * still integrity-checked against the upstream hash and the lock keeps the upstream URL.
     */
    public static ToolchainSyncService withNetwork(NetworkTransport transport, ToolchainDownloadMirror mirror) {
        return new ToolchainSyncService(
                new ToolchainConfigReader(),
                new ResolvingJavaToolchainCatalog(transport),
                new ToolchainLockfileService(),
                new JavaToolchainInstaller(new JavaToolchainDownloader(transport, mirror)));
    }

    ToolchainSyncService(
            ToolchainConfigReader configReader,
            JavaToolchainCatalog catalog,
            ToolchainLockfileService lockfiles,
            JavaToolchainInstaller installer) {
        this.configReader = configReader;
        this.catalog = catalog;
        this.lockfiles = lockfiles;
        this.installer = installer;
    }

    public ToolchainSyncResult sync(
            Path projectRoot,
            ProjectConfig config,
            HostPlatform platform,
            ToolchainStore store) {
        return sync(projectRoot, config, platform, store, false);
    }

    public ToolchainSyncResult sync(
            Path projectRoot,
            ProjectConfig config,
            HostPlatform platform,
            ToolchainStore store,
            boolean refresh) {
        Optional<JavaToolchainRequest> configured = configReader.readJava(projectRoot.resolve("zolt.toml"));
        if (configured.isEmpty()) {
            throw new ActionableException(
                    "Toolchain sync needs an explicit [toolchain.java] table.",
                    "Add [toolchain.java] with version, distribution, and features, then rerun `zolt toolchain sync`.");
        }
        JavaToolchainRequest request = configured.orElseThrow();
        if (request.distribution().isEmpty()) {
            throw new ActionableException(
                    "Toolchain sync needs [toolchain.java].distribution.",
                    "Set distribution to graalvm-community or temurin, then rerun `zolt toolchain sync`.");
        }
        Optional<JavaToolchainRequest> testRequest = configReader.readJavaTest(projectRoot.resolve("zolt.toml"));
        return sync(request, testRequest, projectRoot.resolve("zolt.lock"), platform, store, refresh);
    }

    public ToolchainSyncResult sync(
            JavaToolchainRequest request,
            Path lockfile,
            HostPlatform platform,
            ToolchainStore store) {
        return sync(request, Optional.empty(), lockfile, platform, store, false);
    }

    public ToolchainSyncResult sync(
            JavaToolchainRequest request,
            Path lockfile,
            HostPlatform platform,
            ToolchainStore store,
            boolean refresh) {
        return sync(request, Optional.empty(), lockfile, platform, store, refresh);
    }

    /**
     * Syncs the main toolchain plus the optional {@code [toolchain.java.test]} runtime toolchain.
     * Every distinct request's per-platform matrix is written to the lock as additive
     * {@code [[toolchain.java]]} entries in one deterministic {@code writeJava}, and the current-host
     * archive is installed for each. An equal-version test request dedups against the main entry, so
     * it neither duplicates the lock nor triggers a second download. Returns the main toolchain's
     * install result.
     */
    public ToolchainSyncResult sync(
            JavaToolchainRequest request,
            Optional<JavaToolchainRequest> testRequest,
            Path lockfile,
            HostPlatform platform,
            ToolchainStore store) {
        return sync(request, testRequest, lockfile, platform, store, false);
    }

    public ToolchainSyncResult sync(
            JavaToolchainRequest request,
            Optional<JavaToolchainRequest> testRequest,
            Path lockfile,
            HostPlatform platform,
            ToolchainStore store,
            boolean refresh) {
        HostPlatform effectivePlatform = platform == null ? HostPlatform.current() : platform;
        ToolchainStore effectiveStore = store == null ? ToolchainStore.defaults() : store;
        requireDistribution(request);
        testRequest.ifPresent(this::requireDistribution);

        LinkedHashSet<JavaToolchainRequest> requests = new LinkedHashSet<>();
        requests.add(request);
        testRequest.ifPresent(requests::add);

        List<LockedJavaToolchain> existingLocks = refresh ? List.of() : lockfiles.readJava(lockfile);
        LinkedHashSet<LockedJavaToolchain> allLocks = new LinkedHashSet<>();
        LinkedHashMap<JavaToolchainRequest, List<LockedJavaToolchain>> requestLocks = new LinkedHashMap<>();
        for (JavaToolchainRequest each : requests) {
            List<LockedJavaToolchain> selected = refresh
                    ? catalog.locks(each, effectivePlatform, true)
                    : reusableLocks(existingLocks, each, effectivePlatform)
                            .orElseGet(() -> catalog.locks(each, effectivePlatform, false));
            requestLocks.put(each, selected);
            allLocks.addAll(selected);
        }
        lockfiles.writeJava(lockfile, List.copyOf(allLocks));

        ToolchainSyncResult mainResult = null;
        for (JavaToolchainRequest each : requests) {
            ToolchainSyncResult result = install(
                    each,
                    requestLocks.get(each),
                    effectivePlatform,
                    effectiveStore,
                    lockfile);
            if (mainResult == null) {
                mainResult = result;
            }
        }
        return mainResult;
    }

    private ToolchainSyncResult install(
            JavaToolchainRequest request,
            List<LockedJavaToolchain> locks,
            HostPlatform platform,
            ToolchainStore store,
            Path lockfile) {
        LockedJavaToolchain locked = locks.stream()
                .filter(candidate -> candidate.platform().equals(platform))
                .findFirst()
                .orElseThrow(() -> new ActionableException(
                        "No "
                                + request.distributionLabel()
                                + " Java "
                                + request.version()
                                + " toolchain is available for "
                                + platform.id()
                                + ".",
                        "Choose a Java feature and distribution published for " + platform.id() + "."));
        JavaToolchainArtifact artifact = catalog.artifact(locked).orElseThrow(() -> new ActionableException(
                "No downloadable Java toolchain artifact matches this request.",
                "Refresh the lock or choose a Java feature and distribution published for this platform."));
        boolean downloaded = installer.install(locked, artifact, store);
        return new ToolchainSyncResult(
                lockfile,
                locked,
                store.javaHome(locked),
                store.installed(locked),
                downloaded);
    }

    private static Optional<List<LockedJavaToolchain>> reusableLocks(
            List<LockedJavaToolchain> existing,
            JavaToolchainRequest request,
            HostPlatform platform) {
        ArrayList<LockedJavaToolchain> matching = new ArrayList<>();
        for (LockedJavaToolchain locked : existing) {
            if (sameArtifactRequest(locked.request(), request)) {
                matching.add(withRequest(locked, request));
            }
        }
        if (matching.stream().noneMatch(locked -> locked.platform().equals(platform))) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(matching));
    }

    private static boolean sameArtifactRequest(
            JavaToolchainRequest left,
            JavaToolchainRequest right) {
        return left.version().equals(right.version())
                && left.distribution().equals(right.distribution())
                && left.features().equals(right.features());
    }

    private static LockedJavaToolchain withRequest(
            LockedJavaToolchain locked,
            JavaToolchainRequest request) {
        if (locked.request().equals(request)) {
            return locked;
        }
        return new LockedJavaToolchain(
                locked.id(),
                request,
                locked.platform(),
                locked.resolvedVersion(),
                locked.resolvedDistribution(),
                locked.catalog(),
                locked.artifactUri(),
                locked.artifactSha256(),
                locked.layout());
    }

    private void requireDistribution(JavaToolchainRequest request) {
        if (request.distribution().isEmpty()) {
            throw new ActionableException(
                    "Toolchain sync needs a Java distribution.",
                    "Set distribution to graalvm-community or temurin, then rerun `zolt toolchain sync`.");
        }
    }
}
