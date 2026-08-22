package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.BuiltInCommandCatalog;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;

final class AuthoredCommandsTest {
    private static final BuiltInCommandCatalog BUILT_INS = BuiltInCommandCatalog.fromStrings(
            List.of("build", "check", "task", "version", "versions"));

    @Test
    void retainsTaskAndAliasSourceValuesInDeterministicImmutableMaps() {
        LinkedHashMap<EnvironmentVariableName, String> environment = new LinkedHashMap<>();
        environment.put(new EnvironmentVariableName("RELEASE_CHANNEL"), "preview");
        environment.put(new EnvironmentVariableName("EMPTY"), "");
        AuthoredTask releaseNotes = new AuthoredTask(
                Optional.of("Generate release notes"),
                List.of("zolt", "run", "--workspace", "--member", "tools"),
                Optional.of(new ManifestRelativePath("tools")),
                environment);

        LinkedHashMap<LocalId, AuthoredTask> tasks = new LinkedHashMap<>();
        tasks.put(new LocalId("release-notes"), releaseNotes);
        tasks.put(new LocalId("docs"), task("python3", "-m", "http.server"));
        LinkedHashMap<LocalId, AuthoredAlias> aliases = new LinkedHashMap<>();
        aliases.put(new LocalId("deps"), new AuthoredAlias(List.of("versions")));
        aliases.put(new LocalId("ci"), new AuthoredAlias(List.of("check", "--context", "ci")));

        AuthoredCommands commands = new AuthoredCommands(tasks, aliases, BUILT_INS);
        tasks.clear();
        aliases.clear();
        environment.clear();

        assertEquals(List.of(new LocalId("docs"), new LocalId("release-notes")),
                new ArrayList<>(commands.tasks().keySet()));
        assertEquals(List.of(new LocalId("ci"), new LocalId("deps")),
                new ArrayList<>(commands.aliases().keySet()));
        assertEquals(List.of("zolt", "run", "--workspace", "--member", "tools"),
                commands.tasks().get(new LocalId("release-notes")).run());
        assertEquals(List.of(new EnvironmentVariableName("EMPTY"), new EnvironmentVariableName("RELEASE_CHANNEL")),
                new ArrayList<>(commands.tasks().get(new LocalId("release-notes")).env().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> commands.tasks().clear());
        assertThrows(UnsupportedOperationException.class, () ->
                commands.tasks().get(new LocalId("release-notes")).run().clear());
    }

    @Test
    void rejectsEveryExactBuiltInNameForBothCommandCollections() {
        for (LocalId builtIn : BUILT_INS.names()) {
            assertThrows(IllegalArgumentException.class, () -> new AuthoredCommands(
                    Map.of(builtIn, task("tool")), Map.of(), BUILT_INS));
            assertThrows(IllegalArgumentException.class, () -> new AuthoredCommands(
                    Map.of(), Map.of(builtIn, new AuthoredAlias(List.of("check"))), BUILT_INS));
        }
    }

    @Test
    void aliasesTargetOnlyCommandsInTheExactSuppliedCatalog() {
        assertThrows(IllegalArgumentException.class, () -> new AuthoredCommands(
                Map.of(),
                Map.of(new LocalId("ci"), new AuthoredAlias(List.of("verify"))),
                BUILT_INS));

        AuthoredCommands taskAlias = new AuthoredCommands(
                Map.of(),
                Map.of(new LocalId("fmt"), new AuthoredAlias(List.of("task", "fmt"))),
                BUILT_INS);
        assertEquals(new LocalId("task"),
                taskAlias.aliases().get(new LocalId("fmt")).target());
    }

    @Test
    void rejectsTaskAliasCollisionsAndInvalidStructures() {
        LocalId duplicate = new LocalId("ci");
        assertThrows(IllegalArgumentException.class, () -> new AuthoredCommands(
                Map.of(duplicate, task("tool")),
                Map.of(duplicate, new AuthoredAlias(List.of("check"))),
                BUILT_INS));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredTask(
                Optional.empty(), List.of(), Optional.empty(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredTask(
                Optional.empty(), List.of("  "), Optional.empty(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredAlias(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredAlias(List.of("Not-Kebab")));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredTask(
                Optional.of("  "), List.of("tool"), Optional.empty(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredTask(
                Optional.empty(), List.of("tool", "bad\0argument"), Optional.empty(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredTask(
                Optional.empty(), List.of("tool"), Optional.empty(),
                Map.of(new EnvironmentVariableName("VALUE"), "bad\0value")));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredAlias(
                List.of("check", "bad\0argument")));

        assertEquals(
                List.of("tool", ""),
                new AuthoredTask(
                                Optional.empty(), List.of("tool", ""), Optional.empty(), Map.of())
                        .run());
    }

    @Test
    void appliesStrictIdEnvironmentAndPathValueRules() {
        assertThrows(IllegalArgumentException.class, () -> BuiltInCommandCatalog.fromStrings(
                List.of("not_Canonical")));
        assertThrows(IllegalArgumentException.class, () -> new LocalId("release_notes"));
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentVariableName("APP-ENV"));
        assertThrows(IllegalArgumentException.class, () -> new ManifestRelativePath("../tools"));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredTask(
                Optional.empty(),
                List.of("tool"),
                Optional.empty(),
                Map.of(
                        new EnvironmentVariableName("APP_ENV"), "one",
                        new EnvironmentVariableName("app_env"), "two")));
    }

    @Test
    void preservesCompleteCommandOmissionAndCatalogImmutability() {
        AuthoredCommands commands = AuthoredCommands.empty(BUILT_INS);

        assertTrue(commands.tasks().isEmpty());
        assertTrue(commands.aliases().isEmpty());
        assertEquals(
                List.of("build", "check", "task", "version", "versions"),
                BUILT_INS.names().stream().map(LocalId::value).toList());
        assertThrows(UnsupportedOperationException.class, () -> BUILT_INS.names().clear());
    }

    private static AuthoredTask task(String executable, String... arguments) {
        ArrayList<String> run = new ArrayList<>();
        run.add(executable);
        run.addAll(List.of(arguments));
        return new AuthoredTask(Optional.empty(), run, Optional.empty(), Map.of());
    }
}
