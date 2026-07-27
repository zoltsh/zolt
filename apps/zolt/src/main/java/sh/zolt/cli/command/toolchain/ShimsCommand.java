package sh.zolt.cli.command.toolchain;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.error.ActionableException;
import sh.zolt.home.UserGlobalDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "shims",
        description = "Install opt-in Java toolchain shims.",
        subcommands = {
                ShimsCommand.InstallCommand.class,
                ShimsCommand.StatusCommand.class,
                ShimsCommand.UninstallCommand.class
        })
public final class ShimsCommand implements Runnable {
    static final List<String> SHIMS = List.of("java", "javac", "jar", "javadoc", "jshell", "native-image");

    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(name = "install", description = "Install Java toolchain shim scripts.")
    public static final class InstallCommand implements Callable<Integer> {
        @Option(names = "--shims-dir", description = "Shim directory. Defaults to ~/.zolt/shims.")
        private Path shimsDir = defaultShimsDir();

        @Spec
        private CommandSpec spec;

        @Override
        public Integer call() {
            try {
                Path directory = shimsDir.toAbsolutePath().normalize();
                Files.createDirectories(directory);
                for (String shim : SHIMS) {
                    writeShim(directory.resolve(shim), shim);
                }
                CommandHumanOutput output = CommandHumanOutput.of(spec);
                output.summary("Installed Java toolchain shims");
                output.pointer("wrote", directory.toString());
                output.next("Add " + directory + " to PATH ahead of system Java tools.");
                return 0;
            } catch (IOException exception) {
                throw CommandFailures.user(spec, new ActionableException(
                        "Could not install Java toolchain shims.",
                        "Check that the shim directory is writable and rerun `zolt shims install`."));
            }
        }
    }

    @Command(name = "status", description = "Show Java toolchain shim installation status.")
    public static final class StatusCommand implements Callable<Integer> {
        @Option(names = "--shims-dir", description = "Shim directory. Defaults to ~/.zolt/shims.")
        private Path shimsDir = defaultShimsDir();

        @Spec
        private CommandSpec spec;

        @Override
        public Integer call() {
            Path directory = shimsDir.toAbsolutePath().normalize();
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            output.context("shims directory", directory.toString());
            for (String shim : SHIMS) {
                Path path = directory.resolve(shim);
                output.context(shim, Files.isExecutable(path) ? "installed" : "missing");
            }
            output.context("PATH setup", "add " + directory + " ahead of system Java tools");
            return 0;
        }
    }

    @Command(name = "uninstall", description = "Remove Java toolchain shim scripts.")
    public static final class UninstallCommand implements Callable<Integer> {
        @Option(names = "--shims-dir", description = "Shim directory. Defaults to ~/.zolt/shims.")
        private Path shimsDir = defaultShimsDir();

        @Spec
        private CommandSpec spec;

        @Override
        public Integer call() {
            try {
                Path directory = shimsDir.toAbsolutePath().normalize();
                int removed = 0;
                for (String shim : SHIMS) {
                    if (Files.deleteIfExists(directory.resolve(shim))) {
                        removed++;
                    }
                }
                CommandHumanOutput output = CommandHumanOutput.of(spec);
                output.summary("Removed " + removed + " Java toolchain shims");
                output.pointer("updated", directory.toString());
                return 0;
            } catch (IOException exception) {
                throw CommandFailures.user(spec, new ActionableException(
                        "Could not remove Java toolchain shims.",
                        "Check that the shim directory is writable and rerun `zolt shims uninstall`."));
            }
        }
    }

    private static Path defaultShimsDir() {
        return UserGlobalDirectory.shims();
    }

    private static void writeShim(Path path, String shim) throws IOException {
        Files.writeString(path, """
                #!/usr/bin/env sh
                set -eu

                exec zolt exec -- %s "$@"
                """.formatted(shim));
        path.toFile().setExecutable(true, false);
    }
}
