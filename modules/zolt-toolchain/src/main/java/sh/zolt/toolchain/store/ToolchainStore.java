package sh.zolt.toolchain.store;

import sh.zolt.home.UserGlobalDirectory;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class ToolchainStore {
    private final Path root;

    public ToolchainStore(Path root) {
        this.root = root == null ? UserGlobalDirectory.toolchains() : root;
    }

    public static ToolchainStore defaults() {
        return new ToolchainStore(UserGlobalDirectory.toolchains());
    }

    public Path root() {
        return root;
    }

    public Path installRoot(LockedJavaToolchain locked) {
        return root.resolve("java")
                .resolve(locked.resolvedDistribution().id())
                .resolve(locked.resolvedVersion())
                .resolve(locked.platform().id())
                .resolve("jdk")
                .normalize();
    }

    public Path downloadArchive(LockedJavaToolchain locked, String extension) {
        String cleanExtension = extension == null || extension.isBlank() ? "archive" : extension.strip();
        return root.resolve("downloads")
                .resolve(locked.id() + "-" + locked.platform().id() + "." + cleanExtension)
                .normalize();
    }

    public Path javaHome(LockedJavaToolchain locked) {
        return installRoot(locked)
                .resolve(locked.layout().javaHome())
                .normalize();
    }

    public Path java(LockedJavaToolchain locked) {
        return javaHome(locked).resolve(locked.layout().java()).normalize();
    }

    public Path javac(LockedJavaToolchain locked) {
        return javaHome(locked).resolve(locked.layout().javac()).normalize();
    }

    public Path jar(LockedJavaToolchain locked) {
        return javaHome(locked).resolve(locked.layout().jar()).normalize();
    }

    public Optional<Path> nativeImage(LockedJavaToolchain locked) {
        if (locked.layout().nativeImage().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(javaHome(locked).resolve(locked.layout().nativeImage()).normalize());
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
