package sh.zolt.cli.command.toolchain;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.net.CommandNetwork;
import sh.zolt.config.UserGlobalConfigException;
import sh.zolt.error.ActionableException;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toolchain.ToolchainSyncResult;
import sh.zolt.toolchain.ToolchainSyncService;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "install",
        description = "Resolve and install a managed Java toolchain.",
        subcommands = {ToolchainInstallCommand.JavaCommand.class})
public final class ToolchainInstallCommand implements Runnable {
    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(name = "java", description = "Install a managed Java version.")
    public static final class JavaCommand implements Callable<Integer> {
        private final ToolchainSyncService syncService;

        @Parameters(index = "0", paramLabel = "VERSION", description = "Java feature version, such as 21.")
        private String version;

        @Option(names = "--temurin", description = "Use Eclipse Temurin.")
        private boolean temurin;

        @Option(names = "--graalvm", description = "Use GraalVM Community.")
        private boolean graalvm;

        @Option(names = "--native-image", description = "Require native-image in the Java toolchain.")
        private boolean nativeImage;

        @Option(names = "--refresh", description = "Resolve the newest GA patch instead of reusing a matching lock.")
        private boolean refresh;

        @Option(names = "--policy", hidden = true)
        private String policy = ToolchainPolicy.PREFER_MANAGED.id();

        @Option(names = "--config", hidden = true)
        private Path configPath = GlobalToolchainPaths.defaultConfigPath();

        @Option(names = "--target", hidden = true)
        private String target;

        @Option(names = "--install-root", hidden = true)
        private Path installRoot;

        @Spec
        private CommandSpec spec;

        public JavaCommand() {
            this(null);
        }

        JavaCommand(ToolchainSyncService syncService) {
            this.syncService = syncService;
        }

        private ToolchainSyncService syncService() {
            return syncService != null ? syncService : CommandNetwork.toolchainSyncService(configPath);
        }

        @Override
        public Integer call() {
            try {
                JavaToolchainRequest request = JavaToolchainRequestOptions.javaRequest(
                        version,
                        temurin,
                        graalvm,
                        nativeImage,
                        policy,
                        "the Java toolchain",
                        "Java toolchain");
                ToolchainSyncResult result = syncService().sync(
                        request,
                        GlobalToolchainPaths.lockfile(configPath),
                        HostPlatform.parse(target),
                        new ToolchainStore(installRoot),
                        refresh);
                print(request, result);
                return 0;
            } catch (ActionableException | UserGlobalConfigException exception) {
                throw CommandFailures.user(spec, exception);
            }
        }

        private void print(JavaToolchainRequest request, ToolchainSyncResult result) {
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            output.summary("Installed Java toolchain");
            output.pointer("java", request.distributionLabel() + " " + request.version());
            if (!request.features().isEmpty()) {
                output.pointer("features", request.featuresLabel());
            }
            output.pointer("locked", result.lockfile().toString());
            output.pointer("planned", result.locked().id() + " for " + result.locked().platform().id());
            output.pointer("installed", result.installPath().toString());
            if (result.downloaded()) {
                output.success("Downloaded managed Java toolchain");
            } else if (result.installed()) {
                output.success("Managed Java toolchain is already installed");
            }
        }
    }
}
