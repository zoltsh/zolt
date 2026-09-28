package sh.zolt.cli.command.toolchain;

import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.toolchain.JavaToolchainEnvironment;
import sh.zolt.toolchain.JavaToolchainExecutionService;
import sh.zolt.toolchain.jvm.JavaToolchainSource;
import sh.zolt.toolchain.jvm.ResolvedJavaToolchain;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

public final class CommandJavaToolchainJdkChecker implements JdkChecker {
    private final Path projectRoot;
    private final Path lockRoot;
    private final ProjectConfig config;
    private final JavaToolchainExecutionService toolchains;
    private final HostPlatform platform;
    private final ToolchainStore store;
    private final String commandName;
    private final Optional<JavaToolchainRequest> capturedRequest;
    private final Optional<LockedJavaToolchain> capturedLock;
    private final boolean capturedRequestPinned;
    private final String capturedRequestSource;

    /**
     * The build toolchain checker for a command boundary. Both roots are stated: {@code projectRoot}
     * authors the request, {@code lockRoot} owns the lock (design §4.5). A standalone project passes
     * its own directory twice; there is no one-root form, so no caller can pass a member directory as
     * a lock root by accident.
     */
    public static CommandJavaToolchainJdkChecker forCommand(
            Path projectRoot,
            Path lockRoot,
            ProjectConfig config,
            String toolchainTarget,
            Path toolchainInstallRoot,
            String commandName) {
        return new CommandJavaToolchainJdkChecker(
                projectRoot,
                lockRoot,
                config,
                new JavaToolchainExecutionService(),
                HostPlatform.parse(toolchainTarget),
                new ToolchainStore(toolchainInstallRoot),
                commandName);
    }

    public CommandJavaToolchainJdkChecker(
            Path projectRoot,
            Path lockRoot,
            ProjectConfig config,
            JavaToolchainExecutionService toolchains,
            HostPlatform platform,
            ToolchainStore store,
            String commandName) {
        this.projectRoot = projectRoot;
        this.lockRoot = lockRoot;
        this.config = config;
        this.toolchains = toolchains;
        this.platform = platform;
        this.store = store;
        this.commandName = commandName;
        this.capturedRequest = Optional.empty();
        this.capturedLock = Optional.empty();
        this.capturedRequestPinned = false;
        this.capturedRequestSource = "";
    }

    public CommandJavaToolchainJdkChecker(
            Path projectRoot,
            Path lockRoot,
            ProjectConfig config,
            JavaToolchainExecutionService toolchains,
            HostPlatform platform,
            ToolchainStore store,
            String commandName,
            JavaToolchainRequest capturedRequest,
            boolean capturedRequestPinned,
            Optional<LockedJavaToolchain> capturedLock) {
        this(
                projectRoot,
                lockRoot,
                config,
                toolchains,
                platform,
                store,
                commandName,
                new CapturedToolchain(
                        capturedRequest,
                        capturedRequestPinned,
                        capturedRequestPinned
                                ? "[toolchain.java]"
                                : "[project].java",
                        capturedLock));
    }

    public CommandJavaToolchainJdkChecker(
            Path projectRoot,
            Path lockRoot,
            ProjectConfig config,
            JavaToolchainExecutionService toolchains,
            HostPlatform platform,
            ToolchainStore store,
            String commandName,
            CapturedToolchain captured) {
        this.projectRoot = projectRoot;
        this.lockRoot = lockRoot;
        this.config = config;
        this.toolchains = toolchains;
        this.platform = platform;
        this.store = store;
        this.commandName = commandName;
        this.capturedRequest = Optional.of(captured.request());
        this.capturedLock = captured.locked();
        this.capturedRequestPinned = captured.pinned();
        this.capturedRequestSource = captured.source();
    }

    @Override
    public JdkStatus detect(String requiredVersion) {
        String summary = "Java toolchain is not ready for " + commandName;
        String remediation =
                "Run `zolt toolchain status` for details, then `zolt toolchain sync`, or choose a project with a usable Java toolchain.";
        JavaToolchainEnvironment environment = capturedRequest
                .map(request -> toolchains.environment(
                        request,
                        capturedRequestSource,
                        capturedRequestPinned,
                        capturedLock,
                        platform,
                        store,
                        summary,
                        remediation))
                .orElseGet(() -> toolchains.environment(
                        projectRoot,
                        lockRoot,
                        config,
                        platform,
                        store,
                        summary,
                        remediation));
        ResolvedJavaToolchain resolved = environment.resolved();
        return new JdkStatus(
                Optional.of(environment.javaHome()),
                resolved.java().map(CommandJavaToolchainJdkChecker::absolute),
                resolved.javac().map(CommandJavaToolchainJdkChecker::absolute),
                resolved.jar().map(CommandJavaToolchainJdkChecker::absolute),
                runtimeVersion(resolved),
                Optional.of(compilerIdentity(resolved)),
                requiredVersion);
    }

    private static Optional<String> runtimeVersion(ResolvedJavaToolchain resolved) {
        Optional<String> version = resolved.runtime().version();
        return version.isPresent() ? version : resolved.runtime().featureVersion();
    }

    private String compilerIdentity(ResolvedJavaToolchain resolved) {
        if (resolved.source() == JavaToolchainSource.MANAGED
                && resolved.artifactSha256().isPresent()) {
            return String.join(
                    "\n",
                    "source=managed",
                    "artifact=sha256:" + resolved.artifactSha256().orElseThrow(),
                    "version=" + resolved.runtime().version().orElse("unknown"),
                    "vendor=" + resolved.runtime().vendor().orElse("unknown"),
                    "platform=" + platform.id());
        }
        Path javaHome = resolved.javaHome()
                .map(CommandJavaToolchainJdkChecker::absolute)
                .orElse(null);
        Path javac = resolved.javac()
                .map(CommandJavaToolchainJdkChecker::absolute)
                .orElse(null);
        return String.join(
                "\n",
                "source=ambient",
                "version=" + resolved.runtime().version().orElse("unknown"),
                "vendor=" + resolved.runtime().vendor().orElse("unknown"),
                "platform=" + platform.id(),
                "javaHome=" + (javaHome == null ? "missing" : javaHome),
                "javac=" + (javac == null ? "missing" : javac),
                "javacSize=" + fileSize(javac),
                "javacModified=" + lastModified(javac),
                "release=" + releaseIdentity(javaHome));
    }

    private static String releaseIdentity(Path javaHome) {
        if (javaHome == null) {
            return "missing";
        }
        try {
            Path release = javaHome.resolve("release");
            return Files.isRegularFile(release)
                    ? Base64.getEncoder().encodeToString(Files.readAllBytes(release))
                    : "missing";
        } catch (IOException exception) {
            return "unreadable";
        }
    }

    private static long fileSize(Path path) {
        if (path == null) {
            return -1L;
        }
        try {
            return Files.size(path);
        } catch (IOException exception) {
            return -1L;
        }
    }

    private static long lastModified(Path path) {
        if (path == null) {
            return -1L;
        }
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return -1L;
        }
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }

    public record CapturedToolchain(
            JavaToolchainRequest request,
            boolean pinned,
            String source,
            Optional<LockedJavaToolchain> locked) {
        public CapturedToolchain {
            if (request == null) {
                throw new IllegalArgumentException(
                        "Captured Java toolchain request is required.");
            }
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException(
                        "Captured Java toolchain source is required.");
            }
            locked = locked == null ? Optional.empty() : locked;
        }
    }
}
