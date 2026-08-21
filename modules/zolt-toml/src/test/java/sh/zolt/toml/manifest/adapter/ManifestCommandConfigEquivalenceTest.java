package sh.zolt.toml.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.command.CommandConfig;
import sh.zolt.command.toml.CommandConfigParser;
import sh.zolt.command.ManifestCommandConfigAdapter;

/**
 * A tasks-and-aliases manifest written twice — once in the legacy dialect, once in the final
 * language — asserted to produce the same legacy {@link CommandConfig}.
 *
 * <p>{@link #legacy} is the one helper the cleanup phase deletes with {@link CommandConfigParser}.
 */
final class ManifestCommandConfigEquivalenceTest {
    private static final Set<String> BUILT_IN_COMMANDS =
            Set.of("build", "check", "outdated", "publish", "run", "task", "test");

    private final ManifestProjectConfigLoader loader = new ManifestProjectConfigLoader();

    @Test
    void commandsPairIsEquivalent() {
        CommandConfig legacy = legacy(
                """
                [project]
                name = "commands"
                version = "1.0.0"
                group = "com.example"
                java = "21"

                [commands.tasks.release-notes]
                description = "Generate release notes"
                cmd = ["zolt", "run", "--", "release-notes"]
                cwd = "tools"
                env = { RELEASE_CHANNEL = "preview" }

                [commands.aliases]
                ci = ["check", "--context", "ci", "--all"]
                deps = ["outdated"]
                """);
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

        assertEquals(legacy, adapted);
        assertEquals(
                Optional.of("Generate release notes"),
                adapted.tasks().get("release-notes").description());
        assertEquals(
                List.of("zolt", "run", "--", "release-notes"),
                adapted.tasks().get("release-notes").cmd());
        assertEquals(Optional.of("tools"), adapted.tasks().get("release-notes").cwd());
        assertEquals(
                Map.of("RELEASE_CHANNEL", "preview"), adapted.tasks().get("release-notes").env());
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

    private static CommandConfig legacy(String legacySource) {
        return new CommandConfigParser(BUILT_IN_COMMANDS).parse(legacySource);
    }
}
