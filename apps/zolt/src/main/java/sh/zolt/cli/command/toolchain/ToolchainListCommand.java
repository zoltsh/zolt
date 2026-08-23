package sh.zolt.cli.command.toolchain;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.command.CommandProjectLockfile;
import sh.zolt.cli.command.ProjectCommandContext;
import sh.zolt.config.UserGlobalConfig;
import sh.zolt.config.UserGlobalConfigException;
import sh.zolt.config.UserGlobalConfigParser;
import sh.zolt.error.ActionableException;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import sh.zolt.toolchain.JavaToolchainStatus;
import sh.zolt.toolchain.JavaToolchainStatusService;
import sh.zolt.toolchain.ToolchainConfigReader;
import sh.zolt.toolchain.catalog.BundledJavaToolchainCatalog;
import sh.zolt.toolchain.catalog.JavaToolchainCatalog;
import sh.zolt.toolchain.jvm.ResolvedJavaToolchain;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.lock.ToolchainLockfileService;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "list", description = "List active, locked, and installed Java toolchains.")
public final class ToolchainListCommand implements Callable<Integer> {
    private final ManifestProjectLoader projectLoader;
    private final ToolchainConfigReader toolchainConfigReader;
    private final UserGlobalConfigParser globalConfigParser;
    private final JavaToolchainStatusService statusService;
    private final ToolchainLockfileService lockfiles;
    private final JavaToolchainCatalog catalog;

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Option(names = "--config", hidden = true)
    private Path globalConfigPath = GlobalToolchainPaths.defaultConfigPath();

    @Option(names = "--target", hidden = true)
    private String target;

    @Option(names = "--install-root", hidden = true)
    private Path installRoot;

    @Spec
    private CommandSpec spec;

    public ToolchainListCommand() {
        this(
                new ManifestProjectLoader(),
                new ToolchainConfigReader(),
                new UserGlobalConfigParser(),
                new JavaToolchainStatusService(),
                new ToolchainLockfileService(),
                new BundledJavaToolchainCatalog());
    }

    ToolchainListCommand(
            ManifestProjectLoader projectLoader,
            ToolchainConfigReader toolchainConfigReader,
            UserGlobalConfigParser globalConfigParser,
            JavaToolchainStatusService statusService,
            ToolchainLockfileService lockfiles,
            JavaToolchainCatalog catalog) {
        this.projectLoader = projectLoader;
        this.toolchainConfigReader = toolchainConfigReader;
        this.globalConfigParser = globalConfigParser;
        this.statusService = statusService;
        this.lockfiles = lockfiles;
        this.catalog = catalog;
    }

    @Override
    public Integer call() {
        try {
            Path projectRoot = projectDirectory.path();
            HostPlatform platform = HostPlatform.parse(target);
            ToolchainStore store = new ToolchainStore(installRoot);
            UserGlobalConfig globalConfig = globalConfigParser.read(globalConfigPath);
            ProjectToolchains project = projectToolchains(projectRoot, platform, store);
            Optional<JavaToolchainStatus> global = globalStatus(globalConfig, platform, store);
            List<LockedJavaToolchain> projectLocks = lockfiles.readJava(project.lockfile());
            List<LockedJavaToolchain> globalLocks = lockfiles.readJava(GlobalToolchainPaths.lockfile(globalConfigPath));
            print(projectRoot, project.status(), global, projectLocks, globalLocks, store);
            return 0;
        } catch (ActionableException | UserGlobalConfigException | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }

    /**
     * The project half of the listing, decided once: the active status and the one lockfile that
     * governs this directory.
     *
     * <p>Design §4.5: a member authors its request in its own manifest while the lock that satisfies it
     * lives at the workspace root, so the two directories are stated rather than derived from each
     * other. Without that, the fallback below would evaluate the status against the started directory
     * as its own lock root and consume — or be rejected by — a member-local {@code zolt.lock} that no
     * command may read, before ambient Java is ever considered.
     */
    private ProjectToolchains projectToolchains(
            Path projectRoot,
            HostPlatform platform,
            ToolchainStore store) {
        Path configPath = projectRoot.resolve("zolt.toml");
        if (!Files.isRegularFile(configPath)) {
            return new ProjectToolchains(Optional.empty(), CommandProjectLockfile.path(projectRoot));
        }
        Optional<JavaToolchainRequest> configured = toolchainConfigReader.readJava(configPath);
        if (configured.isPresent()) {
            Path lockfile = CommandProjectLockfile.path(projectRoot);
            return new ProjectToolchains(
                    Optional.of(statusService.status(
                            configured.orElseThrow(),
                            "[toolchain.java]",
                            lockfile,
                            platform,
                            store)),
                    lockfile);
        }
        ProjectCommandContext context = ProjectCommandContext.load(projectLoader, projectRoot);
        return new ProjectToolchains(
                Optional.of(statusService.status(
                        context.projectRoot(),
                        context.lockRoot(),
                        context.config(),
                        platform,
                        store)),
                context.lockfilePath());
    }

    /** The active project status and the lockfile the "project lock" section lists, from one answer. */
    private record ProjectToolchains(Optional<JavaToolchainStatus> status, Path lockfile) {
    }

    private Optional<JavaToolchainStatus> globalStatus(
            UserGlobalConfig config,
            HostPlatform platform,
            ToolchainStore store) {
        return config.toolchainDefaults().java().map(request -> statusService.status(
                request,
                "global default",
                GlobalToolchainPaths.lockfile(globalConfigPath),
                platform,
                store));
    }

    private void print(
            Path projectRoot,
            Optional<JavaToolchainStatus> project,
            Optional<JavaToolchainStatus> global,
            List<LockedJavaToolchain> projectLocks,
            List<LockedJavaToolchain> globalLocks,
            ToolchainStore store) {
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        output.line("Java toolchains");
        output.blankLine();
        output.line("active");
        if (project.isPresent()) {
            output.line("  project: " + statusLine(project.orElseThrow()));
        } else if (global.isPresent()) {
            output.line("  global: " + statusLine(global.orElseThrow()));
        } else {
            output.line("  none");
        }
        output.blankLine();
        output.line("project");
        if (project.isPresent()) {
            JavaToolchainStatus status = project.orElseThrow();
            output.line("  root: " + projectRoot);
            output.line("  request: " + requestLine(status.request()));
            output.line("  source: " + status.requestSource());
            output.line("  resolved: " + resolvedLine(status.resolved()));
        } else {
            output.line("  not configured");
        }
        output.blankLine();
        output.line("global");
        if (global.isPresent()) {
            JavaToolchainStatus status = global.orElseThrow();
            output.line("  request: " + requestLine(status.request()));
            output.line("  resolved: " + resolvedLine(status.resolved()));
        } else {
            output.line("  not configured");
        }
        output.blankLine();
        printLocks(output, "project lock", projectLocks, store);
        printLocks(output, "global lock", globalLocks, store);
        printInstalled(output, projectLocks, globalLocks, store);
    }

    private static String statusLine(JavaToolchainStatus status) {
        return requestLine(status.request()) + " -> " + resolvedLine(status.resolved());
    }

    private static String requestLine(JavaToolchainRequest request) {
        return request.distributionLabel()
                + " "
                + request.version()
                + " features="
                + request.featuresLabel()
                + " policy="
                + request.policy().id();
    }

    private static String resolvedLine(ResolvedJavaToolchain resolved) {
        String location = resolved.javaHome().map(Path::toString).orElse("not installed");
        return (resolved.ok() ? "ok" : "error")
                + " "
                + resolved.source().label()
                + " "
                + location;
    }

    private static void printLocks(
            CommandHumanOutput output,
            String label,
            List<LockedJavaToolchain> locks,
            ToolchainStore store) {
        output.line(label);
        if (locks.isEmpty()) {
            output.line("  none");
            output.blankLine();
            return;
        }
        output.line("  entries: " + locks.size());
        for (LockedJavaToolchain locked : locks) {
            output.line("  " + locked.platform().id()
                    + ": "
                    + locked.resolvedDistribution().id()
                    + " "
                    + locked.resolvedVersion()
                    + " features="
                    + features(locked)
                    + " "
                    + (store.installed(locked) ? "installed" : "not installed"));
        }
        output.blankLine();
    }

    private void printInstalled(
            CommandHumanOutput output,
            List<LockedJavaToolchain> projectLocks,
            List<LockedJavaToolchain> globalLocks,
            ToolchainStore store) {
        output.line("installed");
        output.line("  store: " + store.root());
        List<LockedJavaToolchain> installed = installedCandidates(projectLocks, globalLocks).stream()
                .filter(store::installed)
                .toList();
        if (installed.isEmpty()) {
            output.line("  none");
            return;
        }
        for (LockedJavaToolchain locked : installed) {
            output.line("  " + locked.resolvedDistribution().id()
                    + " "
                    + locked.resolvedVersion()
                    + " "
                    + locked.platform().id()
                    + " features="
                    + features(locked)
                    + " at "
                    + store.javaHome(locked));
        }
    }

    private List<LockedJavaToolchain> installedCandidates(
            List<LockedJavaToolchain> projectLocks,
            List<LockedJavaToolchain> globalLocks) {
        LinkedHashMap<String, LockedJavaToolchain> candidates = new LinkedHashMap<>();
        for (LockedJavaToolchain locked : catalog.available()) {
            candidates.put(key(locked), locked);
        }
        for (LockedJavaToolchain locked : projectLocks) {
            candidates.putIfAbsent(key(locked), locked);
        }
        for (LockedJavaToolchain locked : globalLocks) {
            candidates.putIfAbsent(key(locked), locked);
        }
        return List.copyOf(candidates.values());
    }

    private static String key(LockedJavaToolchain locked) {
        return locked.resolvedDistribution().id()
                + "|"
                + locked.resolvedVersion()
                + "|"
                + locked.platform().id()
                + "|"
                + features(locked);
    }

    private static String features(LockedJavaToolchain locked) {
        if (locked.request().features().isEmpty()) {
            return "none";
        }
        return String.join(",", locked.request().features().stream()
                .map(JavaFeature::id)
                .sorted()
                .toList());
    }
}
