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
import sh.zolt.toolchain.lock.WorkspaceToolchainLockIndex;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import sh.zolt.workspace.service.WorkspaceJdkCheckerResolver;
import sh.zolt.workspace.service.WorkspaceTestRunServiceResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
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
            private final Map<Path, WorkspaceToolchainLockIndex> lockIndexes =
                    new HashMap<>();
            private final Map<Path, WorkspaceToolchainKey> memberKeys =
                    new HashMap<>();

            @Override
            public JdkChecker forMember(
                    sh.zolt.workspace.service.Workspace workspace,
                    sh.zolt.workspace.service.WorkspaceMember member) {
                WorkspaceToolchainKey key = key(workspace, member);
                return new CommandJavaToolchainJdkChecker(
                        member.directory(),
                        workspace.root(),
                        member.config(),
                        services.toolchains(),
                        services.platform(),
                        services.store(),
                        commandName,
                        key.request(),
                        key.pinned(),
                        lockIndex(workspace).find(
                                key.request(),
                                key.platform()));
            }

            @Override
            public Object cacheKey(
                    sh.zolt.workspace.service.Workspace workspace,
                    sh.zolt.workspace.service.WorkspaceMember member,
                    JdkChecker checker) {
                return key(workspace, member);
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
                        + key.storeRoot()
                        + "|"
                        + lockedToolchainIdentity(
                                lockIndex(workspace),
                                key);
            }

            @Override
            public synchronized int lockfileParseCount() {
                return lockIndexes.values().stream()
                        .mapToInt(WorkspaceToolchainLockIndex::parseCount)
                        .sum();
            }

            private synchronized WorkspaceToolchainLockIndex lockIndex(
                    sh.zolt.workspace.service.Workspace workspace) {
                return lockIndexes.computeIfAbsent(
                        workspace.root().toAbsolutePath().normalize(),
                        ignored -> workspace.inputs()
                                .content(workspace.root().resolve("zolt.lock"))
                                .map(WorkspaceToolchainLockIndex::new)
                                .orElseGet(() -> new WorkspaceToolchainLockIndex(
                                        workspace.root().resolve("zolt.lock"))));
            }

            private synchronized WorkspaceToolchainKey key(
                    sh.zolt.workspace.service.Workspace workspace,
                    sh.zolt.workspace.service.WorkspaceMember member) {
                return memberKeys.computeIfAbsent(
                        member.directory().toAbsolutePath().normalize(),
                        ignored -> workspaceToolchainKey(
                                workspace,
                                member,
                                services));
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
            sh.zolt.workspace.service.Workspace workspace,
            sh.zolt.workspace.service.WorkspaceMember member,
            WorkspaceToolchainServices services) {
        ToolchainConfigReader reader = new ToolchainConfigReader();
        Path memberConfig = member.directory().resolve("zolt.toml");
        Optional<JavaToolchainRequest> memberRequest =
                workspace.inputs()
                        .content(memberConfig)
                        .map(reader::readJava)
                        .orElseGet(() -> reader.readJava(memberConfig));
        Path workspaceConfig = workspace.root().resolve("zolt.toml");
        Optional<JavaToolchainRequest> workspaceRequest = memberRequest.isPresent()
                        || !Files.isRegularFile(workspaceConfig)
                ? Optional.empty()
                : workspace.inputs()
                        .content(workspaceConfig)
                        .map(reader::readJava)
                        .orElseGet(() -> reader.readJava(workspaceConfig));
        JavaToolchainRequest request = memberRequest
                .or(() -> workspaceRequest)
                .orElseGet(() -> JavaToolchainRequest.projectDefault(
                        member.config().project().java()));
        return new WorkspaceToolchainKey(
                request,
                services.platform(),
                services.store().root().toAbsolutePath().normalize(),
                memberRequest.isPresent() || workspaceRequest.isPresent());
    }

    private static String lockedToolchainIdentity(
            WorkspaceToolchainLockIndex lockIndex,
            WorkspaceToolchainKey key) {
        Optional<LockedJavaToolchain> locked = lockIndex.find(
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
            Path storeRoot,
            boolean pinned) {
    }
}
