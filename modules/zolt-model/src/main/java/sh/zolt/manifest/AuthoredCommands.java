package sh.zolt.manifest;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable authored tasks and aliases validated against an exact built-in command catalog.
 *
 * <p>Workspace-root/member collision checks and task {@code cwd} real-path containment require
 * effective workspace context and are intentionally deferred to composition and execution.
 */
public final class AuthoredCommands {
    private final Map<LocalId, AuthoredTask> tasks;
    private final Map<LocalId, AuthoredAlias> aliases;

    public AuthoredCommands(
            Map<LocalId, AuthoredTask> tasks,
            Map<LocalId, AuthoredAlias> aliases,
            BuiltInCommandCatalog builtInCommands) {
        this.tasks = ManifestModelValues.immutableSortedMap(
                tasks,
                Comparator.naturalOrder(),
                "Task ID",
                "Task");
        this.aliases = ManifestModelValues.immutableSortedMap(
                aliases,
                Comparator.naturalOrder(),
                "Alias ID",
                "Alias");
        Objects.requireNonNull(builtInCommands, "Built-in command catalog must not be null.");
        validateNames(builtInCommands);
        validateAliasTargets(builtInCommands);
    }

    public static AuthoredCommands empty(BuiltInCommandCatalog builtInCommands) {
        return new AuthoredCommands(Map.of(), Map.of(), builtInCommands);
    }

    public Map<LocalId, AuthoredTask> tasks() {
        return tasks;
    }

    public Map<LocalId, AuthoredAlias> aliases() {
        return aliases;
    }

    private void validateNames(BuiltInCommandCatalog builtInCommands) {
        tasks.keySet().forEach(name -> rejectBuiltInName("Task", name, builtInCommands));
        aliases.keySet().forEach(name -> rejectBuiltInName("Alias", name, builtInCommands));
        tasks.keySet().forEach(name -> {
            if (aliases.containsKey(name)) {
                throw new IllegalArgumentException(
                        "Command ID `" + name + "` cannot be both a task and an alias.");
            }
        });
    }

    private void validateAliasTargets(BuiltInCommandCatalog builtInCommands) {
        aliases.forEach((name, alias) -> {
            if (!builtInCommands.contains(alias.target())) {
                throw new IllegalArgumentException(
                        "Alias `" + name + "` target `" + alias.target()
                                + "` is not a built-in Zolt command.");
            }
        });
    }

    private static void rejectBuiltInName(
            String kind,
            LocalId name,
            BuiltInCommandCatalog builtInCommands) {
        if (builtInCommands.contains(name)) {
            throw new IllegalArgumentException(
                    kind + " ID `" + name + "` is reserved by a built-in Zolt command.");
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof AuthoredCommands commands
                        && tasks.equals(commands.tasks)
                        && aliases.equals(commands.aliases);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tasks, aliases);
    }

    @Override
    public String toString() {
        return "AuthoredCommands[tasks=" + tasks + ", aliases=" + aliases + "]";
    }
}
