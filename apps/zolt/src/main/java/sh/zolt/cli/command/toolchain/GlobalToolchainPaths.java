package sh.zolt.cli.command.toolchain;

import sh.zolt.home.UserGlobalDirectory;
import java.nio.file.Path;

final class GlobalToolchainPaths {
    private GlobalToolchainPaths() {
    }

    static Path defaultConfigPath() {
        return UserGlobalDirectory.configFile();
    }

    static Path lockfile(Path configPath) {
        Path normalized = configPath.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        return (parent == null ? Path.of("global-toolchains.lock") : parent.resolve("global-toolchains.lock"))
                .toAbsolutePath()
                .normalize();
    }
}
