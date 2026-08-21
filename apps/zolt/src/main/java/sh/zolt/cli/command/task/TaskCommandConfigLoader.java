package sh.zolt.cli.command.task;

import sh.zolt.cli.command.CommandProjectDirectory;

final class TaskCommandConfigLoader {
    private final CommandConfigRoots roots;

    TaskCommandConfigLoader() {
        this(new CommandConfigRoots());
    }

    TaskCommandConfigLoader(CommandConfigRoots roots) {
        this.roots = roots;
    }

    LoadedCommandConfig load(CommandProjectDirectory projectDirectory) {
        return roots.load(projectDirectory.path());
    }
}
