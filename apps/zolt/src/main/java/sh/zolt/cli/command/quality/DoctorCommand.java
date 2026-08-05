package sh.zolt.cli.command.quality;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.ZoltCli;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.doctor.SelfHostingCheckResult;
import sh.zolt.doctor.SelfHostingCheckService;
import sh.zolt.error.ActionableError;
import sh.zolt.error.ActionableException;
import sh.zolt.home.UserGlobalDirectory;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.toolchain.JavaToolchainStatus;
import sh.zolt.toolchain.JavaToolchainStatusService;
import sh.zolt.toolchain.TestRuntimeToolchain;
import sh.zolt.toolchain.TestRuntimeToolchainResolver;
import sh.zolt.toolchain.jvm.ResolvedJavaToolchain;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.workspace.toml.WorkspaceConfigParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine;

@Command(name = "doctor", description = "Inspect local Java/JDK/Zolt project health.")
public final class DoctorCommand implements Runnable {
    /**
     * Java baseline used for the environment check when no project pins a version. This mirrors the
     * version {@code zolt init} writes into a fresh {@code zolt.toml}, so a machine that passes the
     * environment check can actually build the project {@code zolt init} is about to create.
     */
    private static final String ENVIRONMENT_JAVA_BASELINE = "21";

    private final ZoltTomlParser tomlParser;
    private final SelfHostingCheckService selfHostingCheckService;
    private final JavaToolchainStatusService toolchainStatusService;

    @Option(names = "--self-hosting", description = "Check whether the project is ready for Zolt-owned self-hosting flows.")
    private boolean selfHosting;

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Spec
    private CommandSpec spec;

    public DoctorCommand() {
        this(
                new ZoltTomlParser(),
                new SelfHostingCheckService(),
                new JavaToolchainStatusService());
    }

    DoctorCommand(
            ZoltTomlParser tomlParser,
            SelfHostingCheckService selfHostingCheckService,
            JavaToolchainStatusService toolchainStatusService) {
        this.tomlParser = tomlParser;
        this.selfHostingCheckService = selfHostingCheckService;
        this.toolchainStatusService = toolchainStatusService;
    }

    @Override
    public void run() {
        try {
            Path projectRoot = projectDirectory.path();
            if (!Files.isDirectory(projectRoot)) {
                throw CommandFailures.user(spec, ActionableError.of(
                        "Cannot inspect " + projectRoot.toAbsolutePath().normalize()
                                + ": it is not an existing directory.",
                        "Create the directory, or pass --directory with a path that exists."));
            }
            if (!Files.isRegularFile(projectRoot.resolve("zolt.toml"))) {
                checkEnvironment(projectRoot);
                return;
            }
            ProjectConfig config = tomlParser.parse(projectRoot.resolve("zolt.toml"));
            boolean ok = printProjectJdkStatus(projectRoot, config);
            Optional<TestRuntimeToolchain> testRuntime = new TestRuntimeToolchainResolver()
                    .resolve(projectRoot, projectRoot, config, HostPlatform.current(), ToolchainStore.defaults());
            if (testRuntime.isPresent()) {
                ok = printTestRuntimeStatus(testRuntime.orElseThrow()) && ok;
            }
            if (selfHosting) {
                SelfHostingCheckResult result = selfHostingCheckService.check(projectRoot);
                printSelfHostingStatus(result);
                ok = ok && result.ok();
            }
            if (!ok) {
                throw new CommandLine.ExecutionException(spec.commandLine(), "Project health check failed.");
            }
        } catch (ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }

    /**
     * Environment-level health for a directory that is not a Zolt project. This is the first command a
     * fresh install runs, so it reports what Zolt can tell about the machine — its own version, the Java
     * toolchain it would resolve, and whether the user-global tree is writable — instead of failing on a
     * missing {@code zolt.toml}.
     */
    private void checkEnvironment(Path projectRoot) {
        printZoltStatus();
        boolean ok = printEnvironmentToolchainStatus(toolchainStore());
        ok = printUserHomeStatus() && ok;
        printSelfHostingSkipNotice();
        printNotAProjectNotice(projectRoot);
        if (!ok) {
            throw new CommandLine.ExecutionException(spec.commandLine(), "Environment health check failed.");
        }
    }

    /**
     * The managed-toolchain store, or empty when the user-global directory cannot be resolved.
     * Resolving it eagerly aborted the command on a bad {@code ZOLT_USER_HOME}, so the user-home check
     * that exists to report exactly that problem never rendered.
     */
    private Optional<ToolchainStore> toolchainStore() {
        try {
            return Optional.of(ToolchainStore.defaults());
        } catch (ActionableException exception) {
            return Optional.empty();
        }
    }

    /** {@code --self-hosting} inspects a project's own layout, so outside one it has nothing to read. */
    private void printSelfHostingSkipNotice() {
        if (!selfHosting) {
            return;
        }
        CommandHumanOutput.of(spec).check(
                "skip",
                "Self-hosting checks need a Zolt project; run them from a directory with zolt.toml.");
    }

    private void printZoltStatus() {
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        output.status("Zolt", "ok");
        output.context("version", ZoltCli.version());
    }

    /**
     * Resolves the Java toolchain through the same service {@code zolt toolchain status} renders, so
     * doctor reports the real resolution outcome rather than a second, divergent probe. Nothing pins a
     * toolchain outside a project, so this asks for an unpinned resolution with no lock metadata.
     */
    private boolean printEnvironmentToolchainStatus(Optional<ToolchainStore> store) {
        if (store.isEmpty()) {
            CommandHumanOutput.of(spec)
                    .check("skip", "JDK check skipped: it needs the user-global Zolt directory.");
            return true;
        }
        return printJdkStatus(toolchainStatusService.status(
                JavaToolchainRequest.projectDefault(ENVIRONMENT_JAVA_BASELINE),
                "environment default",
                false,
                Optional.empty(),
                HostPlatform.current(),
                store.orElseThrow()));
    }

    /**
     * The in-project JDK check runs through the same probe-backed resolution as the environment check
     * and as {@code zolt toolchain status}, so doctor honors {@code [toolchain.java]}, the lockfile, and
     * managed toolchains, and agrees with what {@code zolt build} will actually use. Resolving here
     * rather than through a {@code JdkChecker} is deliberate: doctor reports an unusable toolchain, so
     * it must not take the checker's throw-on-unresolvable path.
     */
    private boolean printProjectJdkStatus(Path projectRoot, ProjectConfig config) {
        return printJdkStatus(toolchainStatusService.status(
                projectRoot,
                config,
                HostPlatform.current(),
                ToolchainStore.defaults()));
    }

    private boolean printJdkStatus(JavaToolchainStatus status) {
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        if (status.ok()) {
            output.status("JDK", "ok");
            return true;
        }
        ResolvedJavaToolchain resolved = status.resolved();
        output.status("JDK status", "error");
        output.context("source", resolved.source().label());
        output.context("JAVA_HOME", resolved.javaHome().map(Path::toString).orElse("not set"));
        output.context("java", resolved.java().map(Path::toString).orElse("missing"));
        output.context("javac", resolved.javac().map(Path::toString).orElse("missing"));
        output.context("jar", resolved.jar().map(Path::toString).orElse("missing"));
        output.context("version", runtimeVersion(resolved).orElse("unknown"));
        CommandHumanOutput errors = CommandHumanOutput.errors(spec);
        for (String problem : resolved.problems()) {
            errors.error(problem);
        }
        return false;
    }

    /** Prefers the Java feature version (21, 25) and falls back to the full runtime version string. */
    private static Optional<String> runtimeVersion(ResolvedJavaToolchain resolved) {
        Optional<String> featureVersion = resolved.runtime().featureVersion();
        return featureVersion.isPresent() ? featureVersion : resolved.runtime().version();
    }

    /**
     * Zolt creates the user-global tree on demand, so an absent directory is healthy as long as the
     * nearest existing ancestor can be written to.
     */
    private boolean printUserHomeStatus() {
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        Path home;
        try {
            home = UserGlobalDirectory.root();
        } catch (ActionableException exception) {
            output.status("Zolt home status", "error");
            CommandHumanOutput.errors(spec).error(exception.getMessage());
            return false;
        }
        Path cache = UserGlobalDirectory.artifactCache();
        if (writable(home) && writable(cache)) {
            output.status("Zolt home", "ok");
            return true;
        }
        output.status("Zolt home status", "error");
        output.context("home", home.toString());
        output.context("cache", cache.toString());
        CommandHumanOutput.errors(spec)
                .error("Zolt cannot write to its user-global directory at " + home + ".");
        return false;
    }

    private void printNotAProjectNotice(Path projectRoot) {
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        Path directory = projectRoot.toAbsolutePath().normalize();
        output.check("skip", "Not a Zolt project: no zolt.toml in " + directory + ".");
        Optional<Path> enclosing = enclosingRoot(directory);
        if (enclosing.isEmpty()) {
            output.action("zolt init");
            return;
        }
        Path root = enclosing.orElseThrow();
        output.context(
                Files.isRegularFile(root.resolve("zolt.toml")) ? "project root" : "workspace root",
                root.toString());
        output.action("zolt doctor --directory " + root);
    }

    /**
     * The project or workspace root enclosing a directory that has no {@code zolt.toml} of its own.
     * Doctor is routinely run from a module subdirectory, and suggesting {@code zolt init} there would
     * nest a second project inside the one that already exists.
     */
    private static Optional<Path> enclosingRoot(Path directory) {
        Path current = directory.getParent();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("zolt.toml"))
                    || Files.isRegularFile(current.resolve(WorkspaceConfigParser.WORKSPACE_FILE))) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private static boolean writable(Path path) {
        Path candidate = path.toAbsolutePath().normalize();
        while (candidate != null && !Files.exists(candidate)) {
            candidate = candidate.getParent();
        }
        return candidate != null && Files.isDirectory(candidate) && Files.isWritable(candidate);
    }

    private boolean printTestRuntimeStatus(TestRuntimeToolchain testRuntime) {
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        if (testRuntime.ready()) {
            output.status("Test runtime JDK", "ok");
            output.context("version", testRuntime.request().version());
            output.context("java", testRuntime.java().map(Path::toString).orElse("resolved"));
            return true;
        }
        output.status("Test runtime JDK status", "error");
        output.context("requested", testRuntime.request().version());
        CommandHumanOutput errors = CommandHumanOutput.errors(spec);
        testRuntime.problem().ifPresent(errors::error);
        testRuntime.remediation().ifPresent(errors::error);
        return false;
    }

    private void printSelfHostingStatus(SelfHostingCheckResult result) {
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        if (result.ok()) {
            output.status("Self-hosting", "ok");
            return;
        }
        output.status("Self-hosting status", "error");
        for (SelfHostingCheckResult.SelfHostingCheck check : result.checks()) {
            String marker = check.ok() ? "ok" : "error";
            output.statusDetail(marker, check.name() + " - " + check.message());
        }
    }
}
