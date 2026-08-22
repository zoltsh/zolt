package sh.zolt.toml.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.command.CommandConfig;
import sh.zolt.command.ManifestCommandConfigAdapter;

/**
 * A tasks-and-aliases manifest asserted to reach the expected {@link CommandConfig} through the
 * final boundary.
 */
final class ManifestCommandConfigAdapterTest {
    private final ManifestProjectConfigLoader loader = new ManifestProjectConfigLoader();

    @Test
    void tasksAndAliasesReachTheCommandConfig() {
        CommandConfig adapted = ManifestCommandConfigAdapter.authored(loader
                .document("""
                        [project]
                        name = "commands"
                        version = "1.0.0"
                        group = "com.example"
                        java = 21

                        [tasks.release-notes]
                        description = "Generate release notes"
                        run = ["zolt", "run", "--", "release-notes"]
                        cwd = "tools"
                        env = { RELEASE_CHANNEL = "preview" }

                        [aliases]
                        ci = ["check", "--context", "ci", "--all"]
                        deps = ["outdated"]
                        """)
                .authored()
                .commands());

        assertEquals(List.of("release-notes"), List.copyOf(adapted.tasks().keySet()));
        assertEquals(
                Optional.of("Generate release notes"),
                adapted.tasks().get("release-notes").description());
        assertEquals(
                List.of("zolt", "run", "--", "release-notes"),
                adapted.tasks().get("release-notes").cmd());
        assertEquals(Optional.of("tools"), adapted.tasks().get("release-notes").cwd());
        assertEquals(
                Map.of("RELEASE_CHANNEL", "preview"), adapted.tasks().get("release-notes").env());
        assertEquals(List.of("ci", "deps"), List.copyOf(adapted.aliases().keySet()));
        assertEquals(List.of("check", "--context", "ci", "--all"), adapted.aliases().get("ci").argv());
        assertEquals(List.of("outdated"), adapted.aliases().get("deps").argv());
    }

    @Test
    void absentCommandDomainIsEmpty() {
        CommandConfig adapted = ManifestCommandConfigAdapter.authored(loader
                .document("""
                        [project]
                        name = "commands"
                        version = "1.0.0"
                        group = "com.example"
                        java = 21
                        """)
                .authored()
                .commands());

        assertEquals(CommandConfig.empty(), adapted);
    }
}
