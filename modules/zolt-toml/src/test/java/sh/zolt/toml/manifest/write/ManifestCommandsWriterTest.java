package sh.zolt.toml.manifest.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static sh.zolt.toml.manifest.ManifestSemanticTestSupport.decodeAuthoredManifest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;
import sh.zolt.manifest.BuiltInCommandCatalog;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredAlias;
import sh.zolt.manifest.authored.AuthoredCommands;
import sh.zolt.manifest.authored.AuthoredTask;
import sh.zolt.toml.schema.FinalManifestSymbols;

final class ManifestCommandsWriterTest {
    private static final BuiltInCommandCatalog BUILT_INS =
            BuiltInCommandCatalog.fromStrings(FinalManifestSymbols.builtInCommandNames());

    @Test
    void emitsTasksAndAliasesInSchemaAndCodePointOrder() {
        AuthoredCommands commands = new AuthoredCommands(
                Map.of(
                        id("zeta"),
                        new AuthoredTask(
                                Optional.empty(),
                                List.of("zolt", "run"),
                                Optional.empty(),
                                Map.of()),
                        id("alpha"),
                        new AuthoredTask(
                                Optional.of("Generate release notes"),
                                List.of("tool", "", "--flag"),
                                Optional.of(new ManifestRelativePath("tools")),
                                Map.of(
                                        environment("Z_CHANNEL"), "preview\nnext",
                                        environment("A_EMPTY"), ""))),
                Map.of(
                        id("zeta-alias"), new AuthoredAlias(List.of("task", "alpha")),
                        id("alpha-alias"), new AuthoredAlias(List.of("check", "--all"))),
                BUILT_INS);

        String output = write(commands);

        assertEquals(
                """
                [tasks.alpha]
                description = "Generate release notes"
                run = ["tool", "", "--flag"]
                cwd = "tools"
                env = { A_EMPTY = "", Z_CHANNEL = "preview\\nnext" }

                [tasks.zeta]
                run = ["zolt", "run"]

                [aliases]
                alpha-alias = ["check", "--all"]
                zeta-alias = ["task", "alpha"]
                """,
                output);
        assertFalse(Toml.parse(output).hasErrors());
        assertFalse(output.contains("{ }"));
        assertEquals(commands, decodeCommands(output));
    }

    @Test
    void omitsExplicitlyEmptyCommandCollections() {
        assertEquals("", write(AuthoredCommands.empty(BUILT_INS)));
    }

    @Test
    void wrapsTaskAndAliasArraysUsingTheCompleteAssignmentWidth() {
        String alias = "a".repeat(89);
        String argument = "x".repeat(91);
        AuthoredCommands commands = new AuthoredCommands(
                Map.of(id("long"), new AuthoredTask(
                        Optional.empty(), List.of(argument), Optional.empty(), Map.of())),
                Map.of(id(alias), new AuthoredAlias(List.of("check"))),
                BUILT_INS);

        String output = write(commands);

        assertEquals(
                "[tasks.long]\nrun = [\n    \"" + argument + "\",\n]\n\n"
                        + "[aliases]\n" + alias + " = [\n    \"check\",\n]\n",
                output);
        assertEquals(commands, decodeCommands(output));
    }

    private static String write(AuthoredCommands commands) {
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();
        new ManifestCommandsWriter().write(emitter, commands);
        return emitter.finish();
    }

    private static AuthoredCommands decodeCommands(String source) {
        return decodeAuthoredManifest("[project]\nname = \"round-trip\"\n\n" + source)
                .commands()
                .orElseThrow();
    }

    private static EnvironmentVariableName environment(String value) {
        return new EnvironmentVariableName(value);
    }

    private static LocalId id(String value) {
        return new LocalId(value);
    }
}
