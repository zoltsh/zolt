package sh.zolt.cli.testcmd;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.writeFakeConsoleJar;
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

final class TestCommandToolchainTest extends TestCommandTestSupport {
    @TempDir
    private Path tempDir;

    @Test
    void testCompilesAndRunsWithManagedJavaToolchain() throws IOException {
        Path projectDir = ManagedJavaToolchainTestFixture.writeProject(tempDir, "managed-test-demo");
        Path cacheRoot = tempDir.resolve("cache");
        writeFakeConsoleJar(cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"));
        writeJUnitConsoleLockfile(projectDir, cacheRoot);
        LockedJavaToolchain locked = ManagedJavaToolchainTestFixture.locked();
        new ToolchainLockfileService().writeJava(projectDir.resolve("zolt.lock"), locked);
        writeDemoTestSource(projectDir);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        Path javacMarker = projectDir.resolve("target/javac-marker.txt");
        ManagedJavaToolchainTestFixture.installManagedToolchain(store, locked, javacMarker);

        var result = execute(
                "test",
                "--directory", projectDir.toString(),
                "--cache-root", cacheRoot.toString(),
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root", tempDir.resolve("toolchains").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/Main.class")));
        assertTrue(Files.exists(projectDir.resolve("target/test-classes/com/example/DemoTest.class")));
        assertTrue(Files.readString(javacMarker).contains("javac=" + store.javac(locked)));
        assertTrue(result.stdout().contains("java=" + store.java(locked)), result.stdout());
        assertTrue(result.stdout().contains("org.junit.platform.console.ConsoleLauncher"), result.stdout());
        assertTrue(result.stdout().contains("Tests passed"), result.stdout());
    }

    @Test
    void testFailsClearlyWhenStrictManagedToolchainIsMissing() throws IOException {
        Path projectDir = ManagedJavaToolchainTestFixture.writeProject(tempDir, "missing-test-demo");
        writeJUnitConsoleLockfile(projectDir, tempDir.resolve("cache"));
        writeDemoTestSource(projectDir);

        var result = execute(
                "test",
                "--directory", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root", tempDir.resolve("missing-toolchains").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Java toolchain is not ready for test"));
        assertTrue(result.stderr().contains("zolt toolchain status"));
        assertTrue(result.stderr().contains("zolt toolchain sync"));
        assertTrue(Files.notExists(projectDir.resolve("target/classes/com/example/Main.class")));
        assertTrue(Files.notExists(projectDir.resolve("target/test-classes/com/example/DemoTest.class")));
    }
}
