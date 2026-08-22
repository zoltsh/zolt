package sh.zolt.toolchain;

import sh.zolt.lockfile.ProjectLockfile;
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
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JavaToolchainStatusService {
    private static final String MANIFEST = "zolt.toml";
    private static final String PROJECT_SOURCE = "[toolchain.java]";
    private static final String WORKSPACE_SOURCE = "[workspace toolchain.java]";

    private final ToolchainConfigReader configReader;
    private final ToolchainLockfileService lockfiles;
    private final JavaToolchainProbe ambientProbe;

    public JavaToolchainStatusService() {
        this(new ToolchainConfigReader(), new ToolchainLockfileService(), new AmbientJavaToolchainProbe());
    }

    /** Status backed by a caller-supplied ambient probe, for hosts that resolve ambient Java themselves. */
    public JavaToolchainStatusService(JavaToolchainProbe ambientProbe) {
        this(new ToolchainConfigReader(), new ToolchainLockfileService(), ambientProbe);
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
        AuthoredRequest authored = readRequest(projectRoot, lockRoot);
        return status(
                authored.request()
                        .orElseGet(() -> JavaToolchainRequest.projectDefault(config.project().java())),
                authored.source(),
                ProjectLockfile.in(lockRoot),
                authored.request().isPresent(),
                effectivePlatform,
                effectiveStore);
    }

    /**
     * The effective {@code [toolchain.java]} request for one project directory.
     *
     * <p>Design §4.5 "Command discovery": a directory a workspace expanded into a member is evaluated
     * with the workspace root's shared configuration, so a member manifest is composed against its
     * root and never standalone. Only a project outside every workspace reads its manifest alone.
     */
    private AuthoredRequest readRequest(Path projectRoot, Path lockRoot) {
        Path projectManifest = projectRoot.resolve(MANIFEST).toAbsolutePath().normalize();
        Path workspaceManifest = workspaceManifest(projectRoot, lockRoot);
        Optional<String> memberPath = memberPath(projectManifest, workspaceManifest);
        if (memberPath.isPresent()) {
            ToolchainConfigReader.MemberToolchains member = configReader.readWorkspaceMember(
                    workspaceManifest, projectManifest, memberPath.orElseThrow());
            return new AuthoredRequest(
                    member.main(),
                    member.mainInherited() ? WORKSPACE_SOURCE : PROJECT_SOURCE);
        }
        Optional<JavaToolchainRequest> configured = configReader.readJava(projectManifest);
        if (configured.isPresent() || projectManifest.equals(workspaceManifest)
                || !Files.isRegularFile(workspaceManifest)) {
            return new AuthoredRequest(configured, PROJECT_SOURCE);
        }
        return new AuthoredRequest(configReader.readJava(workspaceManifest), WORKSPACE_SOURCE);
    }

    /**
     * The workspace root manifest to compose the project against.
     *
     * <p>Callers that already know the workspace pass it as {@code lockRoot}. CLI entries that know
     * only the directory the command was started in pass that directory as both roots, so the
     * enclosing workspace is discovered here instead — otherwise a member would be composed against
     * its own manifest and rejected for the identity it legally inherits (design §4.5).
     */
    private Path workspaceManifest(Path projectRoot, Path lockRoot) {
        Path candidate = lockRoot.resolve(MANIFEST).toAbsolutePath().normalize();
        if (!candidate.equals(projectRoot.resolve(MANIFEST).toAbsolutePath().normalize())) {
            return candidate;
        }
        return configReader.enclosingWorkspaceRoot(projectRoot)
                .map(root -> root.resolve(MANIFEST).toAbsolutePath().normalize())
                .orElse(candidate);
    }

    /**
     * The workspace-relative member path to compose {@code projectManifest} at, or empty when it is
     * not composable as a member of the workspace rooted at {@code workspaceManifest}. A workspace
     * root is its own {@code .} member only when it declares a root {@code [project]} (design §4.4);
     * a virtual root has no project to compose and reports its shared request as authored.
     */
    private Optional<String> memberPath(Path projectManifest, Path workspaceManifest) {
        if (!Files.isRegularFile(workspaceManifest)) {
            return Optional.empty();
        }
        ToolchainConfigReader.ManifestDomains root = configReader.domains(workspaceManifest);
        if (!root.workspace()) {
            return Optional.empty();
        }
        if (projectManifest.equals(workspaceManifest)) {
            return root.project() ? Optional.of(".") : Optional.empty();
        }
        if (!Files.isRegularFile(projectManifest)) {
            return Optional.empty();
        }
        String relative = workspaceManifest.getParent()
                .relativize(projectManifest.getParent())
                .toString()
                .replace(File.separatorChar, '/');
        if (relative.isEmpty() || relative.startsWith("..")) {
            return Optional.empty();
        }
        return Optional.of(relative);
    }

    private record AuthoredRequest(Optional<JavaToolchainRequest> request, String authoredSource) {
        private String source() {
            return request.isEmpty() ? "[project].java" : authoredSource;
        }
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
