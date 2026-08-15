package sh.zolt.build.nativeimage;

import sh.zolt.build.NativeImageException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Real-path and same-file comparisons used by native output preflight. */
final class NativePathOwnership {
    private NativePathOwnership() {
    }

    static boolean overlaps(Path first, Path second) {
        Path ownedFirst = ownershipPath(first);
        Path ownedSecond = ownershipPath(second);
        if (ownedFirst.equals(ownedSecond)
                || ownedFirst.startsWith(ownedSecond)
                || ownedSecond.startsWith(ownedFirst)) {
            return true;
        }
        if (!Files.exists(first) || !Files.exists(second)) {
            return false;
        }
        if (Files.isRegularFile(first) && Files.isDirectory(second)) {
            return aliasesFileWithin(first, second);
        }
        if (Files.isDirectory(first) && Files.isRegularFile(second)) {
            return aliasesFileWithin(second, first);
        }
        try {
            return Files.isSameFile(first, second);
        } catch (IOException exception) {
            throw unreadable(first, exception);
        }
    }

    private static boolean aliasesFileWithin(Path file, Path directory) {
        Path ownedDirectory = ownershipPath(directory);
        try (var paths = Files.walk(ownedDirectory)) {
            return paths.filter(Files::isRegularFile)
                    .anyMatch(candidate -> sameFile(file, candidate));
        } catch (IOException exception) {
            throw unreadable(ownedDirectory, exception);
        } catch (UncheckedIOException exception) {
            throw unreadable(ownedDirectory, exception.getCause());
        }
    }

    private static boolean sameFile(Path first, Path second) {
        try {
            return Files.isSameFile(first, second);
        } catch (IOException exception) {
            throw unreadable(first, exception);
        }
    }

    /** Resolves existing symlink ancestors while retaining any not-yet-created suffix. */
    static Path ownershipPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
        } catch (IOException exception) {
            throw unreadable(path, exception);
        }
    }

    private static NativeImageException unreadable(Path path, IOException exception) {
        return new NativeImageException(
                "Could not validate Native Image output ownership at "
                        + path
                        + ". Check that the path and its ancestors are readable.",
                exception);
    }
}
