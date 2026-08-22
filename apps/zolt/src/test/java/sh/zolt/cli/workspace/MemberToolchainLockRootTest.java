package sh.zolt.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestSupport.CommandResult;

/**
 * Design §4.5/§11.4: a member reads {@code [toolchain.java.test]} from its OWN manifest and the
 * matching locked toolchain from the WORKSPACE ROOT's lock. The two roots are different directories,
 * and the sites here used to pass the member directory as both — so the locked entry was invisible
 * and a {@code require-managed} test runtime reported itself unresolvable from inside a member.
 *
 * <p>The lock entry names an installed toolchain, so "found it" and "did not" are the difference
 * between a ready test runtime and a blocked one rather than two identically worded problems.
 */
final class MemberToolchainLockRootTest {
    private static final String RESOLVED_VERSION = Runtime.version().feature() + ".0.2";

    @TempDir
    private Path tempDir;

    @Test
    void memberToolchainStatusTestRuntimeUsesRootLock() throws IOException {
        Path member = workspace();

        CommandResult result = execute(
                "toolchain", "status",
                "--cwd", member.toString(),
                "--install-root", installRoot().toString());

        String output = result.stdout() + result.stderr();
        assertTrue(output.contains("test runtime ([toolchain.java.test])"), output);
        assertTrue(
                output.contains("  status: ok"),
                () -> "the locked test runtime lives only in the workspace root lock: " + output);
        assertTrue(output.contains(installRoot().toString()), output);
    }

    @Test
    void memberPlanTestRuntimeUsesRootToolchainLock() throws IOException {
        Path member = workspace();

        CommandResult result = execute(
                "plan", "--target", "test",
                "--cwd", member.toString(),
                "--toolchain-install-root", installRoot().toString());

        String output = result.stdout() + result.stderr();
        assertTrue(
                output.contains("testRuntimeJava: " + Runtime.version().feature()),
                output);
        assertFalse(
                output.contains("test-runtime-toolchain"),
                () -> "the locked test runtime lives only in the workspace root lock: " + output);
    }

    /** A member whose test runtime is locked at the root and installed under a test-owned store. */
    private Path workspace() throws IOException {
        Path root = tempDir.resolve("workspace");
        Path member = root.resolve("apps/api");
        Files.createDirectories(member.resolve("src/main/java"));
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = %s
                """.formatted(Runtime.version().feature()));
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "api"

                [toolchain.java.test]
                version = %s
                distribution = "temurin"
                policy = "require-managed"
                """.formatted(Runtime.version().feature()));
        Files.writeString(root.resolve("zolt.lock"), """
                version = 7

                [[toolchain.java]]
                id = "java-temurin-%s"
                request.version = "%s"
                request.distribution = "temurin"
                request.policy = "require-managed"
                platform.os = "%s"
                platform.arch = "%s"
                resolved.version = "%s"
                resolved.distribution = "temurin"
                artifact.catalog = "builtin:java-temurin-%s"
                artifact.uri = "https://example.test/temurin.tar.gz"
                artifact.sha256 = "%s"
                layout.javaHome = "."
                layout.executables.java = "bin/java"
                layout.executables.javac = "bin/javac"
                layout.executables.jar = "bin/jar"
                """.formatted(
                Runtime.version().feature(),
                Runtime.version().feature(),
                hostOs(),
                hostArch(),
                RESOLVED_VERSION,
                Runtime.version().feature(),
                "0".repeat(64)));
        installToolchain();
        return member;
    }

    private Path installRoot() {
        return tempDir.resolve("toolchains");
    }

    /**
     * The layout {@code ToolchainStore} expects for the locked entry above:
     * {@code <root>/java/<distribution>/<resolvedVersion>/<platform>/jdk/bin/{java,javac,jar}}.
     */
    private void installToolchain() throws IOException {
        Path javaHome = installRoot()
                .resolve("java/temurin")
                .resolve(RESOLVED_VERSION)
                .resolve(hostOs() + "-" + hostArch())
                .resolve("jdk");
        Files.createDirectories(javaHome.resolve("bin"));
        for (String executable : new String[] {"java", "javac", "jar"}) {
            Path path = javaHome.resolve("bin").resolve(executable);
            Files.writeString(path, "#!/bin/sh\nexit 0\n");
            if (!path.toFile().setExecutable(true)) {
                throw new IOException("Could not mark the fake toolchain executable: " + path);
            }
        }
    }

    private static String hostOs() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("mac")) {
            return "macos";
        }
        return name.contains("win") ? "windows" : "linux";
    }

    private static String hostArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm") ? "aarch64" : "x64";
    }
}
