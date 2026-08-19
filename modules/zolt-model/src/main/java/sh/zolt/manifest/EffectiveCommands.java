package sh.zolt.manifest;

import java.util.Comparator;
import java.util.Map;

/** Workspace and member commands in one collision-free effective namespace. */
public record EffectiveCommands(
        Map<LocalId, EffectiveValue<AuthoredTask>> tasks,
        Map<LocalId, EffectiveValue<AuthoredAlias>> aliases) {
    public EffectiveCommands {
        tasks = ManifestModelValues.immutableSortedMap(
                tasks,
                Comparator.naturalOrder(),
                "Effective task ID",
                "Effective task");
        aliases = ManifestModelValues.immutableSortedMap(
                aliases,
                Comparator.naturalOrder(),
                "Effective alias ID",
                "Effective alias");
        rejectBuiltInValues(tasks, "Effective task");
        rejectBuiltInValues(aliases, "Effective alias");
        for (LocalId id : tasks.keySet()) {
            if (aliases.containsKey(id)) {
                throw new IllegalArgumentException(
                        "Effective command ID `" + id + "` cannot be both a task and an alias.");
            }
        }
    }

    public static EffectiveCommands empty() {
        return new EffectiveCommands(Map.of(), Map.of());
    }

    private static void rejectBuiltInValues(
            Map<LocalId, ? extends EffectiveValue<?>> values, String label) {
        for (Map.Entry<LocalId, ? extends EffectiveValue<?>> entry : values.entrySet()) {
            if (entry.getValue().origin() == ValueOrigin.BUILT_IN) {
                throw new IllegalArgumentException(
                        label + " `" + entry.getKey() + "` must be authored or inherited.");
            }
        }
    }
}
