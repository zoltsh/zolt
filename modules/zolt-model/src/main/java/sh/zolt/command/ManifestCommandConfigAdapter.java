package sh.zolt.command;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.ManifestSource;
import sh.zolt.manifest.authored.AuthoredAlias;
import sh.zolt.manifest.authored.AuthoredCommands;
import sh.zolt.manifest.authored.AuthoredTask;
import sh.zolt.manifest.effective.EffectiveCommands;
import sh.zolt.manifest.effective.EffectiveValue;

/**
 * Projects the final {@code [tasks.<id>]} and {@code [aliases]} domains onto the legacy
 * {@link CommandConfig}.
 *
 * <p>The final language moved commands out of the nested {@code [commands.tasks.<id>]} and
 * {@code [commands.aliases]} tables into top-level {@code [tasks.<id>]} and {@code [aliases]}
 * (design §15) and renamed a task's argv field from {@code cmd} to {@code run}.
 */
public final class ManifestCommandConfigAdapter {
    private ManifestCommandConfigAdapter() {
    }

    /** The commands exactly as authored in one manifest, with no workspace merging. */
    public static CommandConfig authored(Optional<AuthoredCommands> commands) {
        if (commands.isEmpty()) {
            return CommandConfig.empty();
        }
        AuthoredCommands declared = commands.orElseThrow();
        Map<String, CommandTask> tasks = new LinkedHashMap<>();
        declared.tasks().forEach((id, task) -> tasks.put(id.value(), task(id, task)));
        Map<String, CommandAlias> aliases = new LinkedHashMap<>();
        declared.aliases().forEach((id, alias) -> aliases.put(id.value(), alias(id, alias)));
        return new CommandConfig(aliases, tasks);
    }

    /**
     * The commands after the workspace root and member namespaces are merged (design §15.2).
     *
     * <p>Each task keeps the directory of the manifest that authored it, so a member task resolves
     * its {@code cwd} from the member root while an inherited root task resolves from the workspace
     * root (design §15.1).
     */
    public static CommandConfig effective(EffectiveCommands commands) {
        Map<String, CommandTask> tasks = new LinkedHashMap<>();
        commands.tasks().forEach((id, task) -> tasks.put(
                id.value(), task(id, task.value(), owner(task))));
        Map<String, CommandAlias> aliases = new LinkedHashMap<>();
        commands.aliases().forEach((id, alias) -> aliases.put(id.value(), alias(id, alias.value())));
        return new CommandConfig(aliases, tasks);
    }

    /**
     * The workspace-relative directory that owns one effective command, derived from its manifest
     * source. A root-authored command has no owning subdirectory and returns empty.
     */
    private static Optional<String> owner(EffectiveValue<AuthoredTask> task) {
        return task.source()
                .map(ManifestSource::manifestPath)
                .map(path -> {
                    int separator = path.lastIndexOf('/');
                    return separator < 0 ? "" : path.substring(0, separator);
                })
                .filter(directory -> !directory.isBlank());
    }

    private static CommandTask task(LocalId id, AuthoredTask task) {
        return task(id, task, Optional.empty());
    }

    private static CommandTask task(LocalId id, AuthoredTask task, Optional<String> owner) {
        Map<String, String> environment = new LinkedHashMap<>();
        task.env().forEach((name, value) -> environment.put(name.value(), value));
        return new CommandTask(
                id.value(),
                task.description(),
                task.run(),
                task.cwd().map(ManifestRelativePath::value),
                environment,
                owner);
    }

    private static CommandAlias alias(LocalId id, AuthoredAlias alias) {
        return new CommandAlias(id.value(), alias.argv());
    }

}
