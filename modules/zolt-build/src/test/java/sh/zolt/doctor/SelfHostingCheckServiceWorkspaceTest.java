package sh.zolt.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Design §4.5: {@code zolt doctor --self-hosting} started in a workspace member reads that member's
 * effective project, which inherits identity from {@code [workspace.project]} and may declare
 * {@code workspace = true} dependencies. Composing it standalone would reject both.
 */
final class SelfHostingCheckServiceWorkspaceTest {
    @TempDir
    private Path tempDir;

    @Test
    void checksAWorkspaceMemberAgainstItsComposedProject() throws IOException {
        Path root = tempDir.resolve("platform");
        Files.createDirectories(root);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*", "modules/*"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21
                """);
        Path core = root.resolve("modules/core");
        Files.createDirectories(core);
        Files.writeString(core.resolve("zolt.toml"), """
                [project]
                name = "core"
                """);
        Path member = root.resolve("apps/cli");
        Files.createDirectories(member.resolve("src/main/java"));
        Files.createDirectories(member.resolve("src/test/java"));
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "cli"
                main = "com.example.Main"

                [dependencies]
                "com.example:core" = { workspace = true }

                [dependencies.test]
                "org.junit.platform:junit-platform-console-standalone" = "1.11.4"
                """);

        SelfHostingCheckResult result = new SelfHostingCheckService().check(member);

        assertTrue(
                result.checks().stream().anyMatch(check -> check.name().equals("main class") && check.ok()),
                () -> "expected the inherited project to expose its main class: " + result.checks());
        assertTrue(
                result.checks().stream()
                        .anyMatch(check -> check.name().equals("JUnit Platform Console") && check.ok()),
                () -> "expected the member test lane to be read: " + result.checks());
        assertEquals(
                8,
                result.checks().size(),
                () -> "expected the full self-hosting check set: " + result.checks());
    }
}
