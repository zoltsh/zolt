package sh.zolt.cli.nativeimage;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.sha256;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeCommandWorkspaceTest {
    @TempDir
    private Path tempDir;

    @Test
    void nativeBuildsSelectedWorkspaceMemberFromCli() throws IOException {
        Path workspaceDir = tempDir.resolve("workspace-native");
        NativeCommandTestSupport.writeWorkspaceNativeFixture(workspaceDir);
        Path nativeImage = NativeCommandTestSupport.writeFakeNativeImage(tempDir.resolve("native-image"));
        assertTrue(Files.exists(workspaceDir.resolve("zolt.toml")));
        assertFalse(Files.exists(workspaceDir.resolve("zolt-workspace.toml")));

        CommandResult result = execute(
                "--color=always",
                "native",
                "--workspace",
                "--member", "apps/api",
                "--directory", workspaceDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--native-image", nativeImage.toString());
        Files.delete(workspaceDir.resolve("apps/api/target/native/api"));
        CommandResult quiet = execute(
                "--quiet",
                "native",
                "--workspace",
                "--member", "apps/api",
                "--directory", workspaceDir.toString(),
                "--cache-root", tempDir.resolve("quiet-cache").toString(),
                "--native-image", nativeImage.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("\u001B[32mResolved\u001B[0m workspace dependencies because zolt.lock was missing"));
        assertFalse(result.stdout().contains("\u001B[32mResolved workspace dependencies because zolt.lock was missing"));
        assertTrue(result.stdout().contains("\u001B[32mBuilt\u001B[0m native binary in apps/api"));
        assertFalse(result.stdout().contains("\u001B[32mBuilt native binary in apps/api\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[36m→\u001B[0m wrote \u001B[36m"
                + workspaceDir.resolve("apps/api/target/native/api") + "\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[36m→\u001B[0m logged \u001B[36m"
                + workspaceDir.resolve("apps/api/target/native/native-image.log") + "\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[32m✔\u001B[0m Built native binaries for 1 workspace members"));
        assertFalse(result.stdout().contains("\u001B[32mBuilt native binaries for 1 workspace members\u001B[0m"));
        assertEquals(0, quiet.exitCode(), quiet.stderr());
        assertEquals("", quiet.stdout());
        assertTrue(Files.exists(workspaceDir.resolve("apps/api/target/native/api")));
        assertFalse(Files.exists(workspaceDir.resolve("modules/core/target/native/core")));
    }

    @Test
    void nativeWorkspaceRejectsAllWithExplicitMemberSelection() throws IOException {
        Path workspaceDir = tempDir.resolve("workspace-native-selection");
        NativeCommandTestSupport.writeWorkspaceNativeFixture(workspaceDir);

        CommandResult result = execute(
                "native",
                "--workspace",
                "--all",
                "--member", "apps/api",
                "--cwd", workspaceDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Use either --all or member selection for workspace selection, not both."));
    }

    @Test
    void workspaceNativeKeepsConfiguredMemberPackageOutputsByteIdentical() throws IOException {
        Path workspaceDir = tempDir.resolve("workspace-native-package-isolation");
        NativeCommandTestSupport.writeWorkspaceNativeFixture(workspaceDir);
        Path cache = tempDir.resolve("workspace-native-package-cache");
        Path nativeImage = NativeCommandTestSupport.writeFakeNativeImage(
                tempDir.resolve("workspace-native-package-image"));
        CommandResult packaged = execute(
                "package", "--workspace", "--member", "apps/api",
                "--cwd", workspaceDir.toString(),
                "--cache-root", cache.toString());
        assertEquals(0, packaged.exitCode(), packaged.stderr());
        Path target = workspaceDir.resolve("apps/api/target");
        Map<String, String> before = packageOutputHashes(target);

        for (int run = 0; run < 2; run++) {
            CommandResult result = execute(
                    "native", "--workspace", "--member", "apps/api",
                    "--cwd", workspaceDir.toString(),
                    "--cache-root", cache.toString(),
                    "--native-image", nativeImage.toString());
            assertEquals(0, result.exitCode(), result.stderr());
            assertEquals(before, packageOutputHashes(target));
        }
        assertTrue(Files.isRegularFile(target.resolve("native/input/api-0.1.0.jar")));
        assertTrue(Files.isRegularFile(target.resolve(
                "native/input/api-0.1.0.jar.zolt-package.json")));
    }

    private static Map<String, String> packageOutputHashes(Path target) throws IOException {
        Map<String, String> hashes = new LinkedHashMap<>();
        try (var paths = Files.list(target)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.getFileName().toString().startsWith("api-0.1.0"))
                    .sorted()
                    .toList()) {
                hashes.put(path.getFileName().toString(), sha256(path));
            }
        }
        return hashes;
    }
}
