package sh.zolt.cli.nativeimage;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeSmokeCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void nativeSmokeKeepsReleaseArchiveOutputProjectRelativeFromCli() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        NativeCommandTestSupport.writeProjectConfigWithMain(projectDir, "https://repo.maven.apache.org/maven2");
        Path binary = NativeCommandTestSupport.writeFakeNativeBinary(projectDir);

        CommandResult result = execute(
                "native-smoke",
                "--directory", projectDir.toString(),
                "--binary", Path.of("target/native/zolt").toString(),
                "--work-dir", projectDir.resolve("target/native-smoke").toAbsolutePath().normalize().toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Native smoke status: ok"));
        assertTrue(Files.exists(projectDir.resolve("target/native-smoke/release/demo-0.1.0-linux-x64.tar.gz")));
        assertTrue(Files.exists(binary));
    }

    @Test
    void nativeSmokeUsesModernHumanOutputControls() throws IOException {
        Path colorProject = tempDir.resolve("color-smoke");
        NativeCommandTestSupport.writeProjectConfigWithMain(colorProject, "https://repo.maven.apache.org/maven2");
        NativeCommandTestSupport.writeFakeNativeBinary(colorProject);
        Path quietProject = tempDir.resolve("quiet-smoke");
        NativeCommandTestSupport.writeProjectConfigWithMain(quietProject, "https://repo.maven.apache.org/maven2");
        NativeCommandTestSupport.writeFakeNativeBinary(quietProject);

        CommandResult color = execute(
                "--color=always",
                "native-smoke",
                "--directory", colorProject.toString(),
                "--binary", Path.of("target/native/zolt").toString());
        CommandResult quiet = execute(
                "--quiet",
                "native-smoke",
                "--directory", quietProject.toString(),
                "--binary", Path.of("target/native/zolt").toString());

        assertEquals(0, color.exitCode(), color.stderr());
        assertTrue(color.stdout().contains("Native smoke status: \u001B[32mok\u001B[0m"));
        assertFalse(color.stdout().contains("\u001B[32mNative\u001B[0m smoke status"));
        assertTrue(color.stdout().contains("\u001B[32mok:\u001B[0m Smoked binary"));
        assertFalse(color.stdout().contains("\u001B[32mok: Smoked binary"));
        assertTrue(color.stdout().contains("\u001B[32mok:\u001B[0m Verified release archive"));
        assertFalse(color.stdout().contains("\u001B[32mok: Verified release archive"));
        assertTrue(color.stdout().contains("\u001B[32mok:\u001B[0m Ran generated project"));
        assertFalse(color.stdout().contains("\u001B[32mok: Ran generated project"));
        assertEquals(0, quiet.exitCode(), quiet.stderr());
        assertEquals("", quiet.stdout());
        assertTrue(Files.exists(colorProject.resolve("target/native-smoke/release/demo-0.1.0-linux-x64.tar.gz")));
        assertTrue(Files.exists(quietProject.resolve("target/native-smoke/release/demo-0.1.0-linux-x64.tar.gz")));
    }

    @Test
    void nativeSmokeLogsDoNotDependOnProgressAnimation() throws IOException {
        Path projectDir = tempDir.resolve("progress-smoke");
        NativeCommandTestSupport.writeProjectConfigWithMain(projectDir, "https://repo.maven.apache.org/maven2");
        NativeCommandTestSupport.writeFakeNativeBinary(projectDir);

        CommandResult result = execute(
                "--progress=always",
                "native-smoke",
                "--directory", projectDir.toString(),
                "--binary", Path.of("target/native/zolt").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("Native smoke status: ok"));
        assertTrue(result.stdout().contains("Smoked binary"));
        assertFalse(result.stdout().contains("Still running:"), "native-smoke stdout should not contain progress heartbeat");
        assertFalse(result.stderr().contains("Still running:"), "native-smoke stderr should not contain progress heartbeat");
        assertFalse(result.stdout().contains("\r"), "native-smoke stdout should stay line-based");
        assertFalse(result.stderr().contains("\r"), "native-smoke stderr should stay line-based");
        assertFalse(result.stdout().contains("\u001B["), "native-smoke stdout should not require terminal animation");
        assertTrue(Files.exists(projectDir.resolve("target/native-smoke/release/demo-0.1.0-linux-x64.tar.gz")));
    }

    @Test
    void nativeSmokeDefaultsWorkDirectoryFromConfiguredOutputRoot() throws IOException {
        Path projectDir = tempDir.resolve("demo-output-root");
        NativeCommandTestSupport.writeProjectConfigWithMain(projectDir, "https://repo.maven.apache.org/maven2");
        Files.writeString(
                projectDir.resolve("zolt.toml"),
                Files.readString(projectDir.resolve("zolt.toml")) + """

                [build.output]
                root = ".zolt/build"
                """);
        NativeCommandTestSupport.writeFakeNativeBinary(projectDir);

        CommandResult result = execute(
                "native-smoke",
                "--cwd", projectDir.toString(),
                "--binary", Path.of("target/native/zolt").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(Files.exists(projectDir.resolve(".zolt/build/native-smoke/release/demo-0.1.0-linux-x64.tar.gz")));
    }
}
