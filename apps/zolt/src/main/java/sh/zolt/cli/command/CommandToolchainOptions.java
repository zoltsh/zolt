package sh.zolt.cli.command;

import sh.zolt.cli.command.toolchain.CommandJavaToolchainJdkChecker;
import sh.zolt.cli.command.toolchain.TestRuntimeJdkChecker;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.toolchain.JavaToolchainExecutionService;
import sh.zolt.toolchain.TestRuntimeToolchain;
import sh.zolt.toolchain.TestRuntimeToolchainResolver;
import sh.zolt.toolchain.ToolchainConfigReader;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.lock.ToolchainLockfileService;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import sh.zolt.workspace.service.WorkspaceJdkCheckerResolver;
import sh.zolt.workspace.service.WorkspaceTestRunServiceResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import picocli.CommandLine.Option;

public final class CommandToolchainOptions {
    @Option(names = "--toolchain-target", hidden = true)
    private String toolchainTarget;

    @Option(names = "--toolchain-install-root", hidden = true)
    private Path toolchainInstallRoot;

    public CommandJavaToolchainJdkChecker jdkChecker(
            Path projectRoot,
            ProjectConfig config,
            String commandName) {
        return jdkChecker(projectRoot, projectRoot, config, commandName);
    }

    public CommandJavaToolchainJdkChecker jdkChecker(
            Path projectRoot,
            Path lockRoot,
            ProjectConfig config,
            String commandName) {
        return CommandJavaToolchainJdkChecker.forCommand(
                projectRoot,
                lockRoot,
                config,
                toolchainTarget,
                toolchainInstallRoot,
                commandName);
    }

    /**
     * The {@link JdkChecker} that RUNS tests: the resolved {@code [toolchain.java.test]} runtime
     * toolchain when a project declares one, otherwise {@code buildChecker} (unchanged behavior).
     * Resolution is eager, so an unready test toolchain fails with actionable guidance before compile.
     */
    public JdkChecker testRuntimeRunChecker(Path projectRoot, ProjectConfig config, JdkChecker buildChecker) {
        return testRuntimeRunChecker(projectRoot, projectRoot, config, buildChecker, workspaceToolchainServices());
    }

    private JdkChecker testRuntimeRunChecker(
            Path projectRoot,
            Path lockRoot,
            ProjectConfig config,
            JdkChecker buildChecker,
            WorkspaceToolchainServices services) {
        Optional<TestRuntimeToolchain> resolved = new TestRuntimeToolchainResolver()
                .resolve(projectRoot, lockRoot, config, services.platform(), services.store());
        return resolved.<JdkChecker>map(TestRuntimeJdkChecker::of).orElse(buildChecker);
    }

    public WorkspaceJdkCheckerResolver workspaceJdkCheckers(String commandName) {
        WorkspaceToolchainServices services = workspaceToolchainServices();
        return new WorkspaceJdkCheckerResolver() {
            @Override
            public JdkChecker forMember(
                    sh.zolt.workspace.service.Workspace workspace,
                    sh.zolt.workspace.service.WorkspaceMember member) {
                return jdkChecker(
                        member.directory(),
                        workspace.root(),
                        member.config(),
                        commandName,
                        services);
            }

            @Override
            public Object cacheKey(
                    sh.zolt.workspace.service.Workspace workspace,
                    sh.zolt.workspace.service.WorkspaceMember member,
                    JdkChecker checker) {
                return workspaceToolchainKey(workspace.root(), member, services);
            }

            @Override
            public String compileIdentity(
                    sh.zolt.workspace.service.Workspace workspace,
                    sh.zolt.workspace.service.WorkspaceMember member,
                    JdkChecker checker,
                    Object cacheKey) {
                WorkspaceToolchainKey key =
                        (WorkspaceToolchainKey) cacheKey;
                return key.request()
                        + "|platform="
                        + key.platform().id()
                        + "|store="
                        + key.store().root().toAbsolutePath().normalize()
                        + "|"
                        + lockedToolchainIdentity(workspace.root(), key);
            }
        };
    }

    public WorkspaceTestRunServiceResolver workspaceTestRunServices(
            CommandServiceBundles.TestRunServiceFactory factory,
            String commandName) {
        WorkspaceToolchainServices services = workspaceToolchainServices();
        return (workspace, member) -> {
            CommandJavaToolchainJdkChecker compileChecker = jdkChecker(
                    member.directory(), workspace.root(), member.config(), commandName, services);
            return factory.create(
                    compileChecker,
                    testRuntimeRunChecker(
                            member.directory(), workspace.root(), member.config(), compileChecker, services));
        };
    }

    public WorkspaceTestRunServiceResolver workspaceIntegrationTestRunServices(
            CommandServiceBundles.TestRunServiceFactory factory) {
        WorkspaceToolchainServices services = workspaceToolchainServices();
        return (workspace, member) -> {
            ProjectConfig integrationConfig =
                    member.config().withBuildSettings(member.config().build().asIntegrationTestBuild());
            CommandJavaToolchainJdkChecker compileChecker = jdkChecker(
                    member.directory(), workspace.root(), integrationConfig, "integration-test", services);
            return factory.create(
                    compileChecker,
                    testRuntimeRunChecker(
                            member.directory(), workspace.root(), integrationConfig, compileChecker, services));
        };
    }

    private CommandJavaToolchainJdkChecker jdkChecker(
            Path projectRoot,
            Path lockRoot,
            ProjectConfig config,
            String commandName,
            WorkspaceToolchainServices services) {
        return new CommandJavaToolchainJdkChecker(
                projectRoot,
                lockRoot,
                config,
                services.toolchains(),
                services.platform(),
                services.store(),
                commandName);
    }

    private WorkspaceToolchainServices workspaceToolchainServices() {
        return new WorkspaceToolchainServices(
                new JavaToolchainExecutionService(),
                HostPlatform.parse(toolchainTarget),
                new ToolchainStore(toolchainInstallRoot));
    }

    private static WorkspaceToolchainKey workspaceToolchainKey(
            Path workspaceRoot,
            sh.zolt.workspace.service.WorkspaceMember member,
            WorkspaceToolchainServices services) {
        ToolchainConfigReader reader = new ToolchainConfigReader();
        Optional<JavaToolchainRequest> memberRequest =
                reader.readJava(member.directory().resolve("zolt.toml"));
        Path workspaceConfig = workspaceRoot.resolve("zolt.toml");
        Optional<JavaToolchainRequest> workspaceRequest = memberRequest.isPresent()
                        || !Files.isRegularFile(workspaceConfig)
                ? Optional.empty()
                : reader.readJava(workspaceConfig);
        JavaToolchainRequest request = memberRequest
                .or(() -> workspaceRequest)
                .orElseGet(() -> JavaToolchainRequest.projectDefault(
                        member.config().project().java()));
        return new WorkspaceToolchainKey(
                request,
                services.platform(),
                services.store());
    }

    private static String lockedToolchainIdentity(
            Path workspaceRoot,
            WorkspaceToolchainKey key) {
        Optional<LockedJavaToolchain> locked = new ToolchainLockfileService()
                .findJava(
                        workspaceRoot.resolve("zolt.lock"),
                        key.request(),
                        key.platform());
        if (locked.isEmpty()) {
            return "toolchainLock=none";
        }
        LockedJavaToolchain value = locked.orElseThrow();
        String features = value.request().features().stream()
                .map(feature -> feature.id())
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        return String.join(
                "|",
                "toolchainLock.id=" + value.id(),
                "toolchainLock.request.version=" + value.request().version(),
                "toolchainLock.request.distribution="
                        + value.request().distribution().map(distribution -> distribution.id()).orElse("ambient"),
                "toolchainLock.request.features=" + features,
                "toolchainLock.request.policy=" + value.request().policy().id(),
                "toolchainLock.platform=" + value.platform().id(),
                "toolchainLock.resolved.version=" + value.resolvedVersion(),
                "toolchainLock.resolved.distribution=" + value.resolvedDistribution().id(),
                "toolchainLock.artifact.catalog=" + value.catalog(),
                "toolchainLock.artifact.uri=" + value.artifactUri(),
                "toolchainLock.artifact.sha256=" + value.artifactSha256(),
                "toolchainLock.layout.javaHome=" + value.layout().javaHome(),
                "toolchainLock.layout.java=" + value.layout().java(),
                "toolchainLock.layout.javac=" + value.layout().javac(),
                "toolchainLock.layout.jar=" + value.layout().jar(),
                "toolchainLock.layout.nativeImage=" + value.layout().nativeImage());
    }

    private record WorkspaceToolchainServices(
            JavaToolchainExecutionService toolchains,
            HostPlatform platform,
            ToolchainStore store) {
    }

    private record WorkspaceToolchainKey(
            JavaToolchainRequest request,
            HostPlatform platform,
            ToolchainStore store) {
    }
}
