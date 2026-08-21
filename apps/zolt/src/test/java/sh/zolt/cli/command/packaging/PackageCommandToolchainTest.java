package sh.zolt.cli.command.packaging;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.toolchain.ManagedJavaToolchainTestFixture;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.lock.ToolchainLockfileService;
import sh.zolt.toolchain.store.ToolchainStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageCommandToolchainTest {
    @TempDir
    private Path tempDir;

    @Test
    void packageBuildsInputsWithManagedJavaToolchain() throws IOException {
        Path projectDir = ManagedJavaToolchainTestFixture.writeProject(tempDir, "managed-package-demo");
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = ManagedJavaToolchainTestFixture.locked();
        new ToolchainLockfileService().writeJava(projectDir.resolve("zolt.lock"), locked);
        Path javacMarker = projectDir.resolve("target/javac-marker.txt");
        ManagedJavaToolchainTestFixture.installManagedToolchain(store, locked, javacMarker);

        var result = execute(
                "package",
                "--directory", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root", tempDir.resolve("toolchains").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/Main.class")));
        assertTrue(Files.exists(projectDir.resolve("target/managed-package-demo-0.1.0.jar")));
        assertTrue(Files.readString(javacMarker).contains("javac=" + store.javac(locked)));
        assertTrue(result.stdout().contains("managed-package-demo-0.1.0.jar"), result.stdout());
    }

    @Test
    void packageFailsClearlyWhenStrictManagedToolchainIsMissing() throws IOException {
        Path projectDir = ManagedJavaToolchainTestFixture.writeProject(tempDir, "missing-package-demo");

        var result = execute(
                "package",
                "--directory", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root", tempDir.resolve("missing-toolchains").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Java toolchain is not ready for package"));
        assertTrue(result.stderr().contains("zolt toolchain status"));
        assertTrue(result.stderr().contains("zolt toolchain sync"));
        assertTrue(Files.notExists(projectDir.resolve("target/classes/com/example/Main.class")));
        assertTrue(Files.notExists(projectDir.resolve("target/missing-package-demo-0.1.0.jar")));
    }

    @Test
    void workspacePackageDoesNotResolveCompilerToolchainForBomMember() throws IOException {
        Path workspaceDir = tempDir.resolve("bom-workspace");
        Path bomDir = workspaceDir.resolve("platform-bom");
        Files.createDirectories(bomDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "bom-workspace"

                [workspace.members]
                default = ["platform-bom"]
                include = ["platform-bom"]
                """);
        Files.writeString(bomDir.resolve("zolt.toml"), """
                [project]
                name = "platform-bom"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [toolchain.java]
                version = 21
                distribution = "temurin"
                features = []
                policy = "require-managed"

                [bom]
                members = true
                """);

        var result = execute(
                "package",
                "--workspace",
                "--directory", workspaceDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root", tempDir.resolve("missing-toolchains").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(Files.exists(
                bomDir.resolve("target/publish/platform-bom-1.0.0.pom")));
        assertTrue(result.stdout().contains("platform-bom-1.0.0.pom"), result.stdout());
    }
}
