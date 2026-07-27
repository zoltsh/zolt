package sh.zolt.cli.command.config;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.config.RepositoryOverlayConfig;
import sh.zolt.config.RepositoryOverlayConfigSource;
import sh.zolt.config.UserGlobalConfig;
import sh.zolt.config.UserGlobalConfigException;
import sh.zolt.config.UserGlobalConfigParser;
import sh.zolt.home.UserGlobalDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "config",
        description = "Inspect user-local Zolt config diagnostics.",
        subcommands = {ConfigCommand.ShowCommand.class})
public final class ConfigCommand implements Runnable {
    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(name = "show", description = "Show effective user global config settings.")
    public static final class ShowCommand implements Runnable {
        private final UserGlobalConfigParser parser;

        @Option(names = "--config", description = "User global config path. Defaults to ~/.zolt/config.toml.")
        private Path configPath = defaultConfigPath();

        @Spec
        private CommandSpec spec;

        public ShowCommand() {
            this(new UserGlobalConfigParser());
        }

        ShowCommand(UserGlobalConfigParser parser) {
            this.parser = parser;
        }

        @Override
        public void run() {
            try {
                Path normalizedConfigPath = configPath.toAbsolutePath().normalize();
                boolean configExists = Files.exists(normalizedConfigPath);
                UserGlobalConfig config = parser.read(normalizedConfigPath);
                print(config, configExists);
            } catch (UserGlobalConfigException exception) {
                throw CommandFailures.user(spec, exception);
            }
        }

        private void print(UserGlobalConfig config, boolean configExists) {
            String configPathSource = isDefaultConfigPath(config.configPath()) ? "built-in default" : "flag";
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            output.context("User global config", config.configPath().toString());
            output.context("config path source", configPathSource);
            output.context("config file", configExists ? "present" : "missing");
            output.context("schema version", config.version() + " (source: " + config.sources().version() + ")");
            output.context("machine preferences only", "yes");
            output.context(
                    "project semantics source",
                    "committed zolt.toml, workspace root config, zolt.lock, env references, and command flags");
            output.context("cache.root", config.cacheRoot() + " (source: " + config.sources().cacheRoot() + ")");
            output.context(
                    "repository.downloadConcurrency",
                    config.repository().downloadConcurrency()
                            + " (source: "
                            + config.sources().repositoryDownloadConcurrency()
                            + ")");
            output.context(
                    "repository.executionLane",
                    config.repository().executionLane()
                            + " (source: "
                            + config.sources().repositoryExecutionLane()
                            + ")");
            for (RepositoryOverlayConfig overlay : config.repositoryOverlays().values()) {
                RepositoryOverlayConfigSource source = config.sources()
                        .repositoryOverlays()
                        .getOrDefault(overlay.id(), RepositoryOverlayConfigSource.defaults());
                output.context(
                        "repositoryOverlays." + overlay.id() + ".kind",
                        overlay.kind() + " (source: " + source.kind() + ")");
                output.context(
                        "repositoryOverlays." + overlay.id() + ".enabled",
                        overlay.enabled() + " (source: " + source.enabled() + ")");
            }
            output.context(
                    "defaults.toolchain.java",
                    javaToolchain(config) + " (source: " + config.sources().javaToolchainDefault() + ")");
            output.context("ui.color", config.ui().color() + " (source: " + config.sources().uiColor() + ")");
            output.context("ui.progress", config.ui().progress() + " (source: " + config.sources().uiProgress() + ")");
            output.context(
                    "network.caBundle",
                    config.network().caBundle().map(Path::toString).orElse("none")
                            + " (source: " + config.sources().networkCaBundle() + ")");
            output.context(
                    "network.toolchainMirror",
                    config.network().toolchainMirror().orElse("none")
                            + " (source: " + config.sources().networkToolchainMirror() + ")");
            output.context("buildCache.enabled", Boolean.toString(config.buildCache().enabled()));
            output.context(
                    "buildCache.dir",
                    config.buildCache().directory().map(Path::toString).orElse("none"));
            output.context("local overlay CI policy", "reject with --no-local-overlays or zolt check --context ci");
            output.context(
                    "redaction",
                    "secret values are not read from user global config; repository credentials stay in env references from committed project config");
        }

        private static Path defaultConfigPath() {
            return UserGlobalDirectory.configFile();
        }

        private static boolean isDefaultConfigPath(Path path) {
            return path.toAbsolutePath().normalize().equals(defaultConfigPath());
        }

        private static String javaToolchain(UserGlobalConfig config) {
            return config.toolchainDefaults().java()
                    .map(request -> request.distributionLabel()
                            + " "
                            + request.version()
                            + " (features: "
                            + request.featuresLabel()
                            + ", policy: "
                            + request.policy().id()
                            + ")")
                    .orElse("none");
        }
    }
}
