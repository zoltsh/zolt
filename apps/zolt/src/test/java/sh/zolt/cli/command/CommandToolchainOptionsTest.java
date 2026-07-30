package sh.zolt.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import sh.zolt.cli.toolchain.ManagedJavaToolchainTestFixture;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.lock.ToolchainLockfileService;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
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
    void workspaceCompileIdentitySelectsOnlyMatchingLockRecord()
            throws IOException {
        Path memberDir = tempDir.resolve("apps/api");
        LockedJavaToolchain locked = ManagedJavaToolchainTestFixture.locked();
        Files.createDirectories(memberDir);
        Files.writeString(tempDir.resolve("zolt.toml"), """
                [workspace]
                name = "toolchain-identity-workspace"
                members = ["apps/api"]
                """);
        Files.writeString(memberDir.resolve("zolt.toml"), """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = "%s"

                [toolchain.java]
                version = "%s"
                distribution = "temurin"
                features = []
                policy = "require-managed"
                """.formatted(locked.request().version(), locked.request().version()));
        ToolchainLockfileService lockfiles = new ToolchainLockfileService();
        lockfiles.writeJava(tempDir.resolve("zolt.lock"), locked);
        Workspace workspace =
                new WorkspaceDiscoveryService().discover(tempDir).orElseThrow();
        WorkspaceMember member = workspace.members().getFirst();
        CommandToolchainOptions options = new CommandToolchainOptions();
        new CommandLine(options).parseArgs(
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root", tempDir.resolve("toolchains").toString());
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

        assertNotEquals(original, identity(resolver, workspace, member));
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
