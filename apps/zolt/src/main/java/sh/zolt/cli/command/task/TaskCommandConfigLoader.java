package sh.zolt.cli.command.task;

import sh.zolt.cli.command.CommandProjectDirectory;
import java.nio.file.Path;

final class TaskCommandConfigLoader {
    private final CommandConfigRoots roots;

    TaskCommandConfigLoader() {
        this(new CommandConfigRoots());
    }

    TaskCommandConfigLoader(CommandConfigRoots roots) {
        this.roots = roots;
    }

    LoadedCommandConfig load(CommandProjectDirectory projectDirectory) {
        Path configPath = roots.discoverConfig(projectDirectory.path());
        return new LoadedCommandConfig(configPath, roots.commands(configPath));
    }
}
