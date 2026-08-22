package sh.zolt.cli.command.dependency;

import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.command.CommandProjectLockfile;
import sh.zolt.conflict.DependencyConflictFormatter;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "conflicts", description = "Show version conflicts and selected versions.")
public final class ConflictsCommand implements Runnable {
    private final ManifestWorkspaceLoader workspaceLoader;
    private final ZoltLockfileReader lockfileReader;
    private final DependencyConflictFormatter conflictFormatter;

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Spec
    private CommandSpec spec;

    public ConflictsCommand() {
        this(new ManifestWorkspaceLoader(), new ZoltLockfileReader(), new DependencyConflictFormatter());
    }

    ConflictsCommand(
            ManifestWorkspaceLoader workspaceLoader,
            ZoltLockfileReader lockfileReader,
            DependencyConflictFormatter conflictFormatter) {
        this.workspaceLoader = workspaceLoader;
        this.lockfileReader = lockfileReader;
        this.conflictFormatter = conflictFormatter;
    }

    /**
     * Conflicts are facts of the lock that governs this directory, and the whole lock's conflicts carry
     * their own member attribution. A member directory is governed by the workspace root's lock (design
     * §4.5), so the enclosing workspace decides which lock to read; nothing else here needs the
     * project's configuration.
     */
    @Override
    public void run() {
        try {
            ZoltLockfile lockfile = lockfileReader.read(
                    CommandProjectLockfile.path(projectDirectory.path(), workspaceLoader));
            CommandOutput.printAndFlush(spec, conflictFormatter.format(lockfile));
        } catch (LockfileReadException | WorkspaceConfigException | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }
}
