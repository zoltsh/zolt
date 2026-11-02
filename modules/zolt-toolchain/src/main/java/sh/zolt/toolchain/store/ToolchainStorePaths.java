package sh.zolt.toolchain.store;

import sh.zolt.error.ActionableException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;

/** Resolves existing symlink ancestors even when the final destination does not yet exist. */
public final class ToolchainStorePaths {
    private ToolchainStorePaths() {
    }

    public static Path canonical(Path path) {
        Path existing = path.toAbsolutePath();
        var missing = new ArrayDeque<Path>();
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            missing.addFirst(existing.getFileName());
            existing = existing.getParent();
        }
        try {
            if (existing == null) {
                throw new IOException("no existing ancestor");
            }
            Path resolved = existing.toRealPath();
            for (Path segment : missing) {
                resolved = resolved.resolve(segment);
            }
            return resolved.normalize();
        } catch (IOException exception) {
            throw unsafe(path, "its existing ancestors could not be resolved safely");
        }
    }

    public static Path within(Path root, Path path, boolean allowRoot) {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path absolutePath = path.toAbsolutePath().normalize();
        if (!absolutePath.startsWith(absoluteRoot) || (!allowRoot && absolutePath.equals(absoluteRoot))) {
            throw unsafe(path, "it is outside the managed directory " + absoluteRoot);
        }
        Path realRoot = canonical(root);
        // Resolve before normalization: a symlink followed by '..' has filesystem semantics.
        Path realPath = canonical(path);
        if (!realPath.startsWith(realRoot) || (!allowRoot && realPath.equals(realRoot))) {
            throw unsafe(path, "its symlink target is outside the managed directory " + realRoot);
        }
        return absolutePath;
    }

    private static ActionableException unsafe(Path path, String reason) {
        return new ActionableException(
                "Unsafe Java toolchain path `" + path + "`: " + reason + ".",
                "Use a contained managed toolchain directory and refresh unsafe lock metadata with `zolt toolchain sync --refresh`.");
    }
}
