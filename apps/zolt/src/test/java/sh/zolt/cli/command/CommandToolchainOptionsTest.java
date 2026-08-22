package sh.zolt.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.toolchain.ManagedJavaToolchainTestFixture;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.lock.ToolchainLockfileService;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceJdkCheckerResolver;
import sh.zolt.workspace.service.WorkspaceMember;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

final class CommandToolchainOptionsTest {
    @TempDir
    private Path tempDir;

    @Test
    void workspaceMemberInheritsTheRootToolchainRequest()
            throws IOException {
        LockedJavaToolchain locked = writeSharedToolchainWorkspace(false);
        ToolchainStore store = install(locked);
        Workspace workspace = capturedWorkspace();
        WorkspaceJdkCheckerResolver resolver = options().workspaceJdkCheckers(
                "build");

        var status = resolver.forMember(
                        workspace,
                        workspace.members().getFirst())
                .detect(locked.request().version());

        assertTrue(status.ok(), status.problems().toString());
        assertEquals(
                store.javac(locked).toAbsolutePath().normalize(),
                status.javac().orElseThrow());
        assertEquals(1, resolver.lockfileParseCount());
    }

    /**
     * Design §4.3: a member inherits identity only from {@code [workspace.project]}, never from an
     * unrelated root {@code [project]}. The root here declares a different release and a
     * {@code [toolchain.java]} without a version, so the member's own release — not the root
     * project's — is the one that fills the request (design §11.3).
     */
    @Test
    void workspaceMemberDoesNotInheritAnUnrelatedRootProjectRelease()
            throws IOException {
        LockedJavaToolchain locked = writeSharedToolchainWorkspace(true);
        ToolchainStore store = install(locked);
        Workspace workspace = capturedWorkspace();
        WorkspaceJdkCheckerResolver resolver = options().workspaceJdkCheckers(
                "build");

        var status = resolver.forMember(
                        workspace,
                        workspace.members().getFirst())
                .detect(locked.request().version());

        assertTrue(status.ok(), status.problems().toString());
        assertEquals(
                store.java(locked).toAbsolutePath().normalize(),
                status.java().orElseThrow());
        assertEquals(1, resolver.lockfileParseCount());
    }

    @Test
    void workspaceCompileIdentitySelectsOnlyMatchingLockRecord()
            throws IOException {
        Path memberDir = tempDir.resolve("apps/api");
        LockedJavaToolchain locked = ManagedJavaToolchainTestFixture.locked();
        Files.createDirectories(memberDir);
        Files.writeString(tempDir.resolve("zolt.toml"), """
                [workspace]
                name = "toolchain-identity-workspace"

                [workspace.members]
                include = ["apps/api"]
                """);
        Files.writeString(memberDir.resolve("zolt.toml"), """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.java]
                version = %s
                distribution = "temurin"
                features = []
                policy = "require-managed"
                """.formatted(locked.request().version(), locked.request().version()));
        ToolchainLockfileService lockfiles = new ToolchainLockfileService();
        lockfiles.writeJava(tempDir.resolve("zolt.lock"), locked);
        Workspace workspace = capturedWorkspace();
        WorkspaceMember member = workspace.members().getFirst();
        CommandToolchainOptions options = options();
        WorkspaceJdkCheckerResolver resolver =
                options.workspaceJdkCheckers("build");
        String original = identity(resolver, workspace, member);

        LockedJavaToolchain unrelated = new LockedJavaToolchain(
                "unrelated-windows-toolchain",
                locked.request(),
                HostPlatform.parse("windows-x64"),
                locked.resolvedVersion(),
                locked.resolvedDistribution(),
                "test:unrelated",
                locked.artifactUri(),
                locked.artifactSha256(),
                locked.layout());
        lockfiles.writeJava(
                tempDir.resolve("zolt.lock"),
                List.of(locked, unrelated));
        assertEquals(original, identity(resolver, workspace, member));

        LockedJavaToolchain changedMatch = new LockedJavaToolchain(
                locked.id(),
                locked.request(),
                locked.platform(),
                locked.resolvedVersion(),
                locked.resolvedDistribution(),
                locked.catalog(),
                "https://example.invalid/jdk.tar.gz",
                "a".repeat(64),
                locked.layout());
        lockfiles.writeJava(
                tempDir.resolve("zolt.lock"),
                List.of(changedMatch, unrelated));

        assertEquals(original, identity(resolver, workspace, member));
        assertEquals(1, resolver.lockfileParseCount());
        WorkspaceJdkCheckerResolver nextCommand =
                options.workspaceJdkCheckers("build");
        Workspace nextWorkspace = capturedWorkspace();
        assertNotEquals(
                original,
                identity(
                        nextCommand,
                        nextWorkspace,
                        nextWorkspace.members().getFirst()));
        assertEquals(1, nextCommand.lockfileParseCount());
    }

    private Workspace capturedWorkspace() throws IOException {
        Workspace discovered =
                new ManifestWorkspaceLoader().discover(tempDir).orElseThrow();
        Path lockfile = tempDir.resolve("zolt.lock");
        return discovered.withInputs(
                discovered.inputs().withContent(
                        lockfile,
                        Files.readAllBytes(lockfile)));
    }

    private LockedJavaToolchain writeSharedToolchainWorkspace(
            boolean unrelatedRootProject) throws IOException {
        LockedJavaToolchain locked = ManagedJavaToolchainTestFixture.locked();
        Path memberDir = tempDir.resolve("apps/api");
        Files.createDirectories(memberDir);
        Files.writeString(tempDir.resolve("zolt.toml"), unrelatedRootProject
                ? """
                [workspace]
                name = "shared-toolchain-workspace"

                [workspace.members]
                include = ["apps/api"]

                [project]
                name = "unrelated-root-project"
                version = "0.1.0"
                group = "com.example"
                java = 17

                [toolchain.java]
                distribution = "temurin"
                features = []
                policy = "require-managed"
                """
                : """
                [workspace]
                name = "shared-toolchain-workspace"

                [workspace.members]
                include = ["apps/api"]

                [toolchain.java]
                version = %s
                distribution = "temurin"
                features = []
                policy = "require-managed"
                """.formatted(locked.request().version()));
        Files.writeString(memberDir.resolve("zolt.toml"), """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = %s
                """.formatted(locked.request().version()));
        new ToolchainLockfileService().writeJava(
                tempDir.resolve("zolt.lock"),
                locked);
        return locked;
    }

    private ToolchainStore install(LockedJavaToolchain locked)
            throws IOException {
        ToolchainStore store = new ToolchainStore(
                tempDir.resolve("toolchains"));
        ManagedJavaToolchainTestFixture.installManagedToolchain(
                store,
                locked,
                tempDir.resolve("javac-marker.txt"));
        return store;
    }

    private CommandToolchainOptions options() {
        CommandToolchainOptions options = new CommandToolchainOptions();
        new CommandLine(options).parseArgs(
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root",
                tempDir.resolve("toolchains").toString());
        return options;
    }

    private static String identity(
            WorkspaceJdkCheckerResolver resolver,
            Workspace workspace,
            WorkspaceMember member) {
        var checker = resolver.forMember(workspace, member);
        Object cacheKey = resolver.cacheKey(workspace, member, checker);
        return resolver.compileIdentity(
                workspace,
                member,
                checker,
                cacheKey);
    }
}
