package sh.zolt.toolchain.store;

import sh.zolt.home.UserGlobalDirectory;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class ToolchainStore {
    private final Path root;

    public ToolchainStore(Path root) {
        this.root = ToolchainStorePaths.canonical(root == null ? UserGlobalDirectory.toolchains() : root);
    }

    public static ToolchainStore defaults() {
        return new ToolchainStore(UserGlobalDirectory.toolchains());
    }

    public Path root() {
        return root;
    }

    public Path installRoot(LockedJavaToolchain locked) {
        return checked(root.resolve("java")
                .resolve(locked.resolvedDistribution().id())
                .resolve(locked.resolvedVersion())
                .resolve(locked.platform().id())
                .resolve("jdk"));
    }

    public Path downloadArchive(LockedJavaToolchain locked, String extension) {
        String cleanExtension = extension == null || extension.isBlank() ? "archive" : extension.strip();
        if (!cleanExtension.matches("[A-Za-z0-9][A-Za-z0-9.]*")) {
            throw new IllegalArgumentException("Invalid Java toolchain archive extension.");
        }
        return checked(root.resolve("downloads")
                .resolve(locked.id() + "-" + locked.platform().id() + "." + cleanExtension));
    }

    public Path javaHome(LockedJavaToolchain locked) {
        Path installation = installRoot(locked);
        return ToolchainStorePaths.within(installation, installation.resolve(locked.layout().javaHome()), true);
    }

    public Path java(LockedJavaToolchain locked) {
        return tool(locked, locked.layout().java());
    }

    public Path javac(LockedJavaToolchain locked) {
        return tool(locked, locked.layout().javac());
    }

    public Path jar(LockedJavaToolchain locked) {
        return tool(locked, locked.layout().jar());
    }

    public Optional<Path> nativeImage(LockedJavaToolchain locked) {
        if (locked.layout().nativeImage().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(tool(locked, locked.layout().nativeImage()));
    }

    public Path checked(Path path) {
        return ToolchainStorePaths.within(root, path, false);
    }

    private Path tool(LockedJavaToolchain locked, String relative) {
        return ToolchainStorePaths.within(installRoot(locked), javaHome(locked).resolve(relative), false);
    }

    public boolean installed(LockedJavaToolchain locked) {
        return executable(java(locked))
                && executable(javac(locked))
                && executable(jar(locked))
                && (!locked.request().requiresNativeImage()
                        || nativeImage(locked).map(ToolchainStore::executable).orElse(false));
    }

    private static boolean executable(Path path) {
        return Files.isRegularFile(path) && Files.isExecutable(path);
    }
}
