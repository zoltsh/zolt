package sh.zolt.selfhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceSelfCheckServiceTest {
    @TempDir
    private Path tempDir;

    @Test
    void realWorkspaceDoesNotNeedRootProjectConfigToSelectAppVersion() throws IOException {
        writeWorkspaceApp(tempDir);

        assertTrue(WorkspaceSelfCheckService.usesRealWorkspace(tempDir));
        assertEquals("0.2.0", WorkspaceSelfCheckService.selectedAppConfig(tempDir).project().version());
    }

    private static void writeWorkspaceApp(Path root) throws IOException {
        Path app = root.resolve("zolt");
        Files.createDirectories(app.resolve("src/main/java"));
        Files.createDirectories(app.resolve("src/test/java"));
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "zolt"

                [workspace.members]
                default = ["zolt"]
                include = ["zolt"]
                """);
        Files.writeString(app.resolve("zolt.toml"), """
                [project]
                name = "zolt"
                version = "0.2.0"
                group = "sh.zolt"
                java = 21
                main = "sh.zolt.cli.ZoltCli"

                [dependencies.test]
                "org.junit.platform:junit-platform-console-standalone" = "1.11.4"

                [native]
                name = "zolt"
                output = "target/native"
                args = ["--no-fallback"]
                """);
    }
}
