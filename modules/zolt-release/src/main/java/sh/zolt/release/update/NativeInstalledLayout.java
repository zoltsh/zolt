package sh.zolt.release.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

record NativeInstalledLayout(
        Path installRoot,
        Path binLink,
        Path linkTarget,
        Path versionsDirectory,
        String version) {
    static NativeInstalledLayout detect(Path installRoot, Path currentExecutable) throws IOException {
        Path root = canonicalRoot(installRoot);
        Path binLink = root.resolve("bin/zolt");
        if (!Files.isSymbolicLink(binLink)) {
            throw new NativeUpdateException("Installer-managed native Zolt layouts must use a symlink at " + binLink + ".");
        }
        if (Files.isSymbolicLink(binLink.getParent())) {
            throw new NativeUpdateException("Native bin directory must not be a symbolic link: " + binLink.getParent() + ".");
        }
        managedExecutable(root, currentExecutable);
        // One launcher snapshot; the running managed version may have ceased to be active.
        Path linkTarget = Files.readSymbolicLink(binLink);
        Path linkedExecutable = managedExecutable(root, binLink.getParent().resolve(linkTarget));
        Path versions = root.resolve("versions");
        return new NativeInstalledLayout(root, binLink, linkTarget, versions,
                versions.relativize(linkedExecutable).getName(0).toString());
    }

    static Path managedRoot(Path installRoot, Path currentExecutable) throws IOException {
        Path root = canonicalRoot(installRoot);
        managedExecutable(root, currentExecutable);
        return root;
    }

    private static Path canonicalRoot(Path installRoot) throws IOException {
        if (!Files.isDirectory(installRoot)) {
            throw new NativeUpdateException("Installer-managed native Zolt layouts require an existing installation at "
                    + installRoot.toAbsolutePath().normalize() + ". Reinstall with the native installer.");
        }
        return installRoot.toRealPath();
    }

    private static Path managedExecutable(Path root, Path executable) throws IOException {
        Path current = executable.toRealPath();
        Path versions = root.resolve("versions");
        if (current.startsWith(versions)) {
            Path relative = versions.relativize(current);
            if (relative.getNameCount() == 3
                    && !relative.getName(0).toString().startsWith(".")
                    && !relative.getName(0).toString().endsWith(".tmp")
                    && relative.getName(1).toString().equals("bin")
                    && relative.getName(2).toString().equals("zolt")
                    && Files.isRegularFile(current) && Files.isExecutable(current)) {
                return current;
            }
        }
        throw new NativeUpdateException("Installer-managed native Zolt layouts must use versioned installs under "
                + versions + ". Expected a completed managed executable at <version>/bin/zolt; found " + current + ".");
    }
}
