package sh.zolt.cli.command;

import sh.zolt.cli.command.toolchain.CommandJavaToolchainJdkChecker;
import sh.zolt.cli.command.toolchain.TestRuntimeJdkChecker;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkStatus;
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
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class WorkspaceCommandToolchainState {
    private final JavaToolchainExecutionService toolchains;
    private final HostPlatform platform;
    private final ToolchainStore store;
    private final String commandName;
    private final WorkspaceToolchainLockIndex lockIndex;
    private final Map<Path, CapturedMainToolchain> mainByMember =
            new HashMap<>();
    private final Map<Path, Optional<JavaToolchainRequest>> testByMember =
            new HashMap<>();
    private final Map<WorkspaceToolchainKey, JdkChecker> mainCheckers =
            new HashMap<>();
    private final Map<WorkspaceToolchainKey, TestRuntimeToolchain> testToolchains =
            new HashMap<>();
    private int testIdentityCalculations;
    private int testIdentityHits;

    WorkspaceCommandToolchainState(
            Workspace workspace,
            JavaToolchainExecutionService toolchains,
            HostPlatform platform,
            ToolchainStore store,
            String commandName) {
        this.toolchains = toolchains;
        this.platform = platform;
        this.store = store;
        this.commandName = commandName;
        String lockfileContent = workspace.inputs()
                .content(workspace.root().resolve("zolt.lock"))
                .orElse("");
        this.lockIndex = new WorkspaceToolchainLockIndex(lockfileContent);
    }

    synchronized Object mainKey(
            Workspace workspace,
            WorkspaceMember member) {
        return capturedMain(workspace, member).key();
    }

    synchronized JdkChecker mainChecker(
            Workspace workspace,
            WorkspaceMember member) {
        CapturedMainToolchain captured =
                capturedMain(workspace, member);
        return mainCheckers.computeIfAbsent(
                captured.key(),
                ignored -> memoize(checker(
                        workspace,
                        member,
                        member.config(),
                        captured.request(),
                        captured.pinned(),
                        captured.source())));
    }

    synchronized String compileIdentity(Object cacheKey) {
        WorkspaceToolchainKey key = (WorkspaceToolchainKey) cacheKey;
        return key.request()
                + "|platform="
                + key.platform().id()
                + "|store="
                + key.storeRoot()
                + "|"
                + lockedToolchainIdentity(key);
    }

    synchronized JdkChecker testRuntimeChecker(
            Workspace workspace,
            WorkspaceMember member) {
        Optional<JavaToolchainRequest> request =
                capturedTest(workspace, member);
        if (request.isEmpty()) {
            return mainChecker(workspace, member);
        }
        WorkspaceToolchainKey key =
                key(workspace, request.orElseThrow(), true);
        TestRuntimeToolchain resolved = testToolchains.get(key);
        if (resolved != null) {
            testIdentityHits++;
        } else {
            testIdentityCalculations++;
            JavaToolchainRequest testRequest = request.orElseThrow();
            resolved = new TestRuntimeToolchainResolver().resolveCaptured(
                    testRequest,
                    lockIndex.find(testRequest, platform),
                    member.config(),
                    platform,
                    store);
            testToolchains.put(key, resolved);
        }
        TestRuntimeToolchain memberToolchain = new TestRuntimeToolchain(
                resolved.request(),
                resolved.status(),
                member.config().project().java());
        return TestRuntimeJdkChecker.of(memberToolchain);
    }

    int lockfileParseCount() {
        return lockIndex.parseCount();
    }

    synchronized int testIdentityCalculations() {
        return testIdentityCalculations;
    }

    synchronized int testIdentityHits() {
        return testIdentityHits;
    }

    private synchronized CapturedMainToolchain capturedMain(
            Workspace workspace,
            WorkspaceMember member) {
        Path memberConfig =
                member.directory().resolve("zolt.toml")
                        .toAbsolutePath().normalize();
        return mainByMember.computeIfAbsent(
                memberConfig,
                ignored -> readMain(workspace, member, memberConfig));
    }

    private synchronized Optional<JavaToolchainRequest> capturedTest(
            Workspace workspace,
            WorkspaceMember member) {
        Path memberConfig =
                member.directory().resolve("zolt.toml")
                        .toAbsolutePath().normalize();
        return testByMember.computeIfAbsent(
                memberConfig,
                ignored -> capturedMain(workspace, member)
                        .configContent()
                        .flatMap(content -> new ToolchainConfigReader()
                                .readJavaTest(content)));
    }

    private CapturedMainToolchain readMain(
            Workspace workspace,
            WorkspaceMember member,
            Path memberConfig) {
        ToolchainConfigReader reader = new ToolchainConfigReader();
        Optional<String> memberContent =
                workspace.inputs().content(memberConfig);
        Optional<JavaToolchainRequest> memberRequest =
                memberContent.flatMap(reader::readJava);
        if (memberRequest.isPresent()) {
            return captured(
                    workspace,
                    memberRequest.orElseThrow(),
                    true,
                    "[toolchain.java]",
                    memberContent);
        }
        Optional<String> workspaceContent = workspace.inputs()
                .content(workspace.configPath());
        Optional<JavaToolchainRequest> workspaceRequest =
                workspaceContent.flatMap(reader::readJava);
        if (workspaceRequest.isPresent()) {
            return captured(
                    workspace,
                    workspaceRequest.orElseThrow(),
                    true,
                    "[workspace toolchain.java]",
                    workspaceContent);
        }
        return captured(
                workspace,
                JavaToolchainRequest.projectDefault(
                        member.config().project().java()),
                false,
                "[project].java",
                Optional.empty());
    }

    private CapturedMainToolchain captured(
            Workspace workspace,
            JavaToolchainRequest request,
            boolean pinned,
            String source,
            Optional<String> configContent) {
        return new CapturedMainToolchain(
                request,
                pinned,
                source,
                configContent,
                key(workspace, request, pinned));
    }

    private WorkspaceToolchainKey key(
            Workspace workspace,
            JavaToolchainRequest request,
            boolean pinned) {
        return new WorkspaceToolchainKey(
                workspace.root().toAbsolutePath().normalize(),
                request,
                platform,
                store.root().toAbsolutePath().normalize(),
                pinned);
    }

    private JdkChecker checker(
            Workspace workspace,
            WorkspaceMember member,
            ProjectConfig config,
            JavaToolchainRequest request,
            boolean pinned,
            String source) {
        WorkspaceToolchainKey key = key(workspace, request, pinned);
        return new CommandJavaToolchainJdkChecker(
                member.directory(),
                workspace.root(),
                config,
                toolchains,
                platform,
                store,
                commandName,
                new CommandJavaToolchainJdkChecker.CapturedToolchain(
                        request,
                        pinned,
                        source,
                        lockIndex.find(request, key.platform())));
    }

    private static JdkChecker memoize(JdkChecker checker) {
        Map<String, JdkStatus> statuses = new LinkedHashMap<>();
        return requiredVersion -> {
            synchronized (statuses) {
                return statuses.computeIfAbsent(
                        requiredVersion,
                        checker::detect);
            }
        };
    }

    private String lockedToolchainIdentity(
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
                        + value.request().distribution()
                                .map(distribution -> distribution.id())
                                .orElse("ambient"),
                "toolchainLock.request.features=" + features,
                "toolchainLock.request.policy="
                        + value.request().policy().id(),
                "toolchainLock.platform=" + value.platform().id(),
                "toolchainLock.resolved.version=" + value.resolvedVersion(),
                "toolchainLock.resolved.distribution="
                        + value.resolvedDistribution().id(),
                "toolchainLock.artifact.catalog=" + value.catalog(),
                "toolchainLock.artifact.uri=" + value.artifactUri(),
                "toolchainLock.artifact.sha256=" + value.artifactSha256(),
                "toolchainLock.layout.javaHome=" + value.layout().javaHome(),
                "toolchainLock.layout.java=" + value.layout().java(),
                "toolchainLock.layout.javac=" + value.layout().javac(),
                "toolchainLock.layout.jar=" + value.layout().jar(),
                "toolchainLock.layout.nativeImage="
                        + value.layout().nativeImage());
    }

    private record CapturedMainToolchain(
            JavaToolchainRequest request,
            boolean pinned,
            String source,
            Optional<String> configContent,
            WorkspaceToolchainKey key) {
        private CapturedMainToolchain {
            configContent = configContent == null
                    ? Optional.empty()
                    : configContent;
        }
    }

    private record WorkspaceToolchainKey(
            Path workspaceRoot,
            JavaToolchainRequest request,
            HostPlatform platform,
            Path storeRoot,
            boolean pinned) {
    }
}
