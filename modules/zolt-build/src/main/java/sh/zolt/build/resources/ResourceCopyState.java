package sh.zolt.build.resources;

import sh.zolt.build.ResourceCopyException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Portable ownership inventory: resource removal must never sweep compiler-produced files. */
final class ResourceCopyState {
    private static final String FILE_NAME = ".zolt-resources.state";
    private static final String HEADER = "zolt-resources-v1";

    static void reconcile(Path output, Set<Path> current) {
        Path root = output.toAbsolutePath().normalize();
        Path state = root.resolve(FILE_NAME);
        try {
            validatePath(root, Path.of(FILE_NAME), true);
            Set<Path> previous = read(state);
            for (Path path : previous) {
                validatePath(root, path, false);
            }
            for (Path path : current) {
                validatePath(root, path, false);
            }
            Set<Path> compilerOwned = sh.zolt.build.output.CompilerOutputInventory.read(root)
                    .map(value -> value.files().keySet()).orElse(Set.of());
            for (Path path : previous.stream().sorted().toList()) {
                if (!current.contains(path) && !compilerOwned.contains(path)) {
                    Files.deleteIfExists(root.resolve(path));
                    pruneEmptyParents(root, root.resolve(path).getParent());
                }
            }
            // Record every planned destination before copying. A failed copy remains owned, so a
            // subsequent retry or source removal can reconcile partially produced output safely.
            write(state, current);
        } catch (IOException | IllegalArgumentException exception) {
            throw new ResourceCopyException(
                    "Could not reconcile copied resources under " + root
                            + ". Check the output permissions or run `zolt clean` to reset resource state.",
                    exception);
        }
    }

    private static Set<Path> read(Path state) throws IOException {
        if (!Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
            return Set.of();
        }
        List<String> lines = Files.readAllLines(state, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !HEADER.equals(lines.getFirst())) {
            throw new IOException("Invalid resource ownership inventory at " + state);
        }
        Set<Path> paths = new HashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            paths.add(Path.of(new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8)));
        }
        return paths;
    }

    private static void validatePath(Path root, Path relative, boolean state) throws IOException {
        String name = relative.toString().replace(java.io.File.separatorChar, '/');
        if (name.isEmpty() || relative.isAbsolute() || !relative.equals(relative.normalize())
                || relative.startsWith("..") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                || (!state && relative.equals(Path.of(FILE_NAME)))) {
            throw new IOException("Invalid owned resource path: " + relative);
        }
        Path target = root.resolve(relative);
        for (Path path = target; path != null && path.startsWith(root); path = path.getParent()) {
            if (Files.isSymbolicLink(path)) {
                throw new IOException("Resource output must not traverse a symbolic link: " + path);
            }
        }
    }

    private static void pruneEmptyParents(Path root, Path directory) throws IOException {
        while (directory != null && !directory.equals(root)) {
            try {
                Files.deleteIfExists(directory);
            } catch (DirectoryNotEmptyException exception) {
                return;
            }
            directory = directory.getParent();
        }
    }

    private static void write(Path state, Set<Path> paths) throws IOException {
        if (paths.isEmpty() && !Files.exists(state)) {
            return;
        }
        StringBuilder content = new StringBuilder(HEADER).append('\n');
        paths.stream().map(path -> path.toString().replace('\\', '/')).sorted().forEach(path -> content
                .append(Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8))).append('\n'));
        String updated = content.toString();
        if (Files.isRegularFile(state) && Files.readString(state).equals(updated)) {
            return;
        }
        Files.createDirectories(state.getParent());
        Path temporary = Files.createTempFile(state.getParent(), ".zolt-resource-state-", ".tmp");
        try {
            Files.writeString(temporary, updated, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, state, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, state, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
