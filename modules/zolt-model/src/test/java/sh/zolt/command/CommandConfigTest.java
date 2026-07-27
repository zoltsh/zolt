package sh.zolt.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class CommandConfigTest {
    @Test
    void preservesOrderAndMakesCollectionsImmutable() {
        Map<String, CommandAlias> aliases = new LinkedHashMap<>();
        aliases.put("b", new CommandAlias("b", List.of("build")));
        aliases.put("t", new CommandAlias("t", List.of("test")));

        Map<String, CommandTask> tasks = new LinkedHashMap<>();
        tasks.put("fmt", new CommandTask("fmt", Optional.empty(), List.of("scripts/format"), Optional.empty(), Map.of()));
        tasks.put("docs", new CommandTask(
                "docs",
                Optional.of("Serve docs"),
                List.of("python3", "-m", "http.server"),
                Optional.of("docs"),
                Map.of("APP_ENV", "local")));

        CommandConfig config = new CommandConfig(aliases, tasks);

        assertEquals(List.of("b", "t"), List.copyOf(config.aliases().keySet()));
        assertEquals(List.of("fmt", "docs"), List.copyOf(config.tasks().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> config.aliases().put(
                "ci",
                new CommandAlias("ci", List.of("check"))));
        assertThrows(UnsupportedOperationException.class, () -> config.tasks().get("docs").cmd().add("--bind"));
        assertThrows(UnsupportedOperationException.class, () -> config.tasks().get("docs").env().put("PORT", "8000"));
    }

    @Test
    void validatesCommandConvenienceNames() {
        assertTrue(CommandConfigRules.isValidName("fmt"));
        assertTrue(CommandConfigRules.isValidName("api.fmt"));
        assertTrue(CommandConfigRules.isValidName("run-api"));
        assertFalse(CommandConfigRules.isValidName(""));
        assertFalse(CommandConfigRules.isValidName("-fmt"));
        assertFalse(CommandConfigRules.isValidName("fmt task"));
    }

    @Test
    void recognizesEnvironmentNamesAndAssignments() {
        assertTrue(CommandConfigRules.isValidEnvironmentName("APP_ENV"));
        assertTrue(CommandConfigRules.isEnvironmentAssignmentPrefix("APP_ENV=local"));
        assertFalse(CommandConfigRules.isValidEnvironmentName("APP-ENV"));
        assertFalse(CommandConfigRules.isEnvironmentAssignmentPrefix("--flag=value"));
    }

    @Test
    void recognizesExternalAndShellLikeAliasTargets() {
        assertTrue(CommandConfigRules.isExecutablePath("./scripts/check"));
        assertTrue(CommandConfigRules.isExecutablePath("scripts/check"));
        assertTrue(CommandConfigRules.isShellExecutable("bash"));
        assertTrue(CommandConfigRules.isShellExecutable("powershell.exe"));
        assertTrue(CommandConfigRules.looksLikeShellString("check && test"));
        assertFalse(CommandConfigRules.looksLikeShellString("--format"));
    }

    @Test
    void validatesPortableRelativeNormalizedCwdPaths() {
        assertTrue(CommandConfigRules.isRelativeNormalizedPath("docs"));
        assertTrue(CommandConfigRules.isRelativeNormalizedPath("tools/member"));
        assertFalse(CommandConfigRules.isRelativeNormalizedPath("/tmp"));
        assertFalse(CommandConfigRules.isRelativeNormalizedPath("C:/tmp"));
        assertFalse(CommandConfigRules.isRelativeNormalizedPath("tools\\member"));
        assertFalse(CommandConfigRules.isRelativeNormalizedPath("../outside"));
        assertFalse(CommandConfigRules.isRelativeNormalizedPath("docs/../scripts"));
        assertFalse(CommandConfigRules.isRelativeNormalizedPath("docs/./scripts"));
        assertFalse(CommandConfigRules.isRelativeNormalizedPath("docs//scripts"));
        assertFalse(CommandConfigRules.isRelativeNormalizedPath("docs/"));
    }
}
