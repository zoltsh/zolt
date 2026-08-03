package sh.zolt.toolchain;

import sh.zolt.project.ProjectConfig;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toolchain.jvm.AmbientJavaToolchainProbe;
import sh.zolt.toolchain.jvm.JavaRuntimeInfo;
import sh.zolt.toolchain.jvm.JavaToolchainProbe;
import sh.zolt.toolchain.jvm.JavaToolchainSource;
import sh.zolt.toolchain.jvm.ResolvedJavaToolchain;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.lock.ToolchainLockfileService;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JavaToolchainStatusService {
    private final ToolchainConfigReader configReader;
    private final ToolchainLockfileService lockfiles;
    private final JavaToolchainProbe ambientProbe;

    public JavaToolchainStatusService() {
        this(new ToolchainConfigReader(), new ToolchainLockfileService(), new AmbientJavaToolchainProbe());
    }

    JavaToolchainStatusService(
            ToolchainConfigReader configReader,
            ToolchainLockfileService lockfiles,
            JavaToolchainProbe ambientProbe) {
        this.configReader = configReader;
        this.lockfiles = lockfiles;
        this.ambientProbe = ambientProbe;
    }

    public JavaToolchainStatus status(Path projectRoot, ProjectConfig config) {
        return status(projectRoot, config, HostPlatform.current(), ToolchainStore.defaults());
    }

    public JavaToolchainStatus status(
            Path projectRoot,
            ProjectConfig config,
            HostPlatform platform,
            ToolchainStore store) {
        return status(projectRoot, projectRoot, config, platform, store);
    }

    public JavaToolchainStatus status(
            Path projectRoot,
            Path lockRoot,
            ProjectConfig config,
            HostPlatform platform,
            ToolchainStore store) {
        HostPlatform effectivePlatform = platform == null ? HostPlatform.current() : platform;
        ToolchainStore effectiveStore = store == null ? ToolchainStore.defaults() : store;
        Optional<JavaToolchainRequest> configured = readConfigured(projectRoot);
        Optional<JavaToolchainRequest> workspaceConfigured = configured.isPresent()
                ? Optional.empty()
                : readWorkspaceConfigured(projectRoot, lockRoot);
        JavaToolchainRequest request = configured
                .or(() -> workspaceConfigured)
                .orElseGet(() -> JavaToolchainRequest.projectDefault(config.project().java()));
        String source = requestSource(configured, workspaceConfigured);
        return status(
                request,
                source,
                lockRoot.resolve("zolt.lock"),
                configured.isPresent() || workspaceConfigured.isPresent(),
                effectivePlatform,
                effectiveStore);
    }

    private Optional<JavaToolchainRequest> readConfigured(Path projectRoot) {
        return configReader.readJava(projectRoot.resolve("zolt.toml"));
    }

    private Optional<JavaToolchainRequest> readWorkspaceConfigured(Path projectRoot, Path lockRoot) {
        Path projectConfig = projectRoot.resolve("zolt.toml").toAbsolutePath().normalize();
        Path workspaceConfig = lockRoot.resolve("zolt.toml").toAbsolutePath().normalize();
        if (projectConfig.equals(workspaceConfig) || !Files.isRegularFile(workspaceConfig)) {
            return Optional.empty();
        }
        return configReader.readJava(workspaceConfig);
    }

    private static String requestSource(
            Optional<JavaToolchainRequest> configured,
            Optional<JavaToolchainRequest> workspaceConfigured) {
        if (configured.isPresent()) {
            return "[toolchain.java]";
        }
        if (workspaceConfigured.isPresent()) {
            return "[workspace toolchain.java]";
        }
        return "[project].java";
    }

    public JavaToolchainStatus status(
            JavaToolchainRequest request,
            String requestSource,
            Path lockfile,
            HostPlatform platform,
            ToolchainStore store) {
        HostPlatform effectivePlatform = platform == null ? HostPlatform.current() : platform;
        ToolchainStore effectiveStore = store == null ? ToolchainStore.defaults() : store;
        return status(
                request,
                requestSource,
                lockfile,
                true,
                effectivePlatform,
                effectiveStore);
    }

    public JavaToolchainStatus status(
            JavaToolchainRequest request,
            String requestSource,
            boolean projectPinned,
            Optional<LockedJavaToolchain> locked,
            HostPlatform platform,
            ToolchainStore store) {
        HostPlatform effectivePlatform = platform == null ? HostPlatform.current() : platform;
        ToolchainStore effectiveStore = store == null ? ToolchainStore.defaults() : store;
        ResolvedJavaToolchain resolved = resolve(
                locked,
                request,
                projectPinned,
                effectivePlatform,
                effectiveStore);
        return new JavaToolchainStatus(request, requestSource, resolved);
    }

    private JavaToolchainStatus status(
            JavaToolchainRequest request,
            String requestSource,
            Path lockfile,
            boolean projectPinned,
            HostPlatform platform,
            ToolchainStore store) {
        ResolvedJavaToolchain resolved = resolve(
                lockfile,
                request,
                projectPinned,
                platform,
                store);
        return new JavaToolchainStatus(request, requestSource, resolved);
    }

    private ResolvedJavaToolchain resolve(
            Path lockfile,
            JavaToolchainRequest request,
            boolean projectPinned,
            HostPlatform platform,
            ToolchainStore store) {
        return resolve(
                lockfiles.findJava(lockfile, request, platform),
                request,
                projectPinned,
                platform,
                store);
    }

    private ResolvedJavaToolchain resolve(
            Optional<LockedJavaToolchain> locked,
            JavaToolchainRequest request,
            boolean projectPinned,
            HostPlatform platform,
            ToolchainStore store) {
        if (!projectPinned) {
            return ambientProbe.resolve(request);
        }
        if (request.policy() == ToolchainPolicy.ALLOW_SYSTEM) {
            ResolvedJavaToolchain ambient = ambientProbe.resolve(request);
            if (ambient.ok()) {
                return ambient;
            }
            return managedOrAmbient(locked, request, platform, store, ambient);
        }
        return managedOrAmbient(locked, request, platform, store, null);
    }

    private ResolvedJavaToolchain managedOrAmbient(
            Optional<LockedJavaToolchain> locked,
            JavaToolchainRequest request,
            HostPlatform platform,
            ToolchainStore store,
            ResolvedJavaToolchain attemptedAmbient) {
        if (locked.isEmpty()) {
            String note = "Java toolchain lock metadata is missing for " + platform.id()
                    + "; run `zolt toolchain sync`.";
            if (request.policy() == ToolchainPolicy.REQUIRE_MANAGED) {
                return managedProblem(request, Optional.empty(), List.of(note), List.of(note));
            }
            return ambientFallback(request, attemptedAmbient, note);
        }
        LockedJavaToolchain lockedToolchain = locked.orElseThrow();
        if (store.installed(lockedToolchain)) {
            return managed(lockedToolchain, store);
        }
        String note = "Locked managed Java toolchain is not installed at "
                + store.javaHome(lockedToolchain)
                + "; falling back to ambient Java.";
        if (request.policy() == ToolchainPolicy.REQUIRE_MANAGED) {
            return managedProblem(
                    request,
                    Optional.of(store.javaHome(lockedToolchain)),
                    List.of("Managed Java toolchain is locked but not installed at "
                            + store.javaHome(lockedToolchain)
                            + "."),
                    List.of("Lock entry: " + lockedToolchain.id() + " for " + lockedToolchain.platform().id()));
        }
        return ambientFallback(request, attemptedAmbient, note);
    }

    private ResolvedJavaToolchain ambientFallback(
            JavaToolchainRequest request,
            ResolvedJavaToolchain attemptedAmbient,
            String note) {
        ResolvedJavaToolchain ambient = attemptedAmbient == null ? ambientProbe.resolve(request) : attemptedAmbient;
        return withNote(ambient, note);
    }

    private static ResolvedJavaToolchain managed(LockedJavaToolchain locked, ToolchainStore store) {
        return new ResolvedJavaToolchain(
                JavaToolchainSource.MANAGED,
                Optional.of(store.javaHome(locked)),
                Optional.of(store.java(locked)),
                Optional.of(store.javac(locked)),
                Optional.of(store.jar(locked)),
                store.nativeImage(locked),
                new JavaRuntimeInfo(
                        Optional.of(locked.resolvedVersion()),
                        Optional.of(locked.request().version()),
                        Optional.of(locked.resolvedDistribution().id())),
                locked.request(),
                List.of(),
                List.of("Lock entry: " + locked.id() + " for " + locked.platform().id()));
    }

    private static ResolvedJavaToolchain managedProblem(
            JavaToolchainRequest request,
            Optional<Path> javaHome,
            List<String> problems,
            List<String> notes) {
        return new ResolvedJavaToolchain(
                JavaToolchainSource.MANAGED,
                javaHome,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                JavaRuntimeInfo.empty(),
                request,
                problems,
                notes);
    }

    private static ResolvedJavaToolchain withNote(ResolvedJavaToolchain resolved, String note) {
        List<String> notes = new ArrayList<>(resolved.notes());
        notes.add(note);
        return new ResolvedJavaToolchain(
                resolved.source(),
                resolved.javaHome(),
                resolved.java(),
                resolved.javac(),
                resolved.jar(),
                resolved.nativeImage(),
                resolved.runtime(),
                resolved.request(),
                resolved.problems(),
                notes);
    }
}
