package sh.zolt.workspace.discovery;

import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.unicode.Unicode17Portability;
import sh.zolt.workspace.service.WorkspaceDirectoryEvidence;
import sh.zolt.workspace.service.WorkspaceInputs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Reads each authoritative workspace config path at most once.
 */
final class WorkspaceInputCapture {
    private final Map<Path, byte[]> captured = new LinkedHashMap<>();
    private final Set<Path> missing = new LinkedHashSet<>();
    private final Map<EvidenceKey, WorkspaceDirectoryEvidence> directoryEvidence =
            new LinkedHashMap<>();

    Optional<String> read(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        byte[] existing = captured.get(normalized);
        if (existing != null) {
            return Optional.of(text(existing));
        }
        if (missing.contains(normalized)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(normalized)) {
            missing.add(normalized);
            return Optional.empty();
        }
        try {
            byte[] content = Files.readAllBytes(normalized);
            captured.put(normalized, content);
            return Optional.of(text(content));
        } catch (IOException exception) {
            throw new WorkspaceConfigException(
                    "Could not read workspace config at "
                            + normalized
                            + ". Check that the file exists and is readable.");
        }
    }

    /** Every non-dot child directory, the exact set a {@code *} pattern segment consults. */
    List<Path> wildcardDirectories(Path directory) {
        return select(
                directory,
                WorkspaceDirectoryEvidence.WILDCARD,
                path -> !path.getFileName().toString().startsWith(".")
                        && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS));
    }

    /** Every child whose name normalizes to {@code segment}, what a literal segment consults. */
    List<Path> namedEntries(Path directory, String segment) {
        return select(
                directory,
                segment,
                path -> segment.equals(
                        Unicode17Portability.normalizeNfc(path.getFileName().toString())));
    }

    private List<Path> select(Path directory, String selector, Predicate<Path> relevant) {
        Path normalized = directory.toAbsolutePath().normalize();
        try {
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceConfigException(
                        "Workspace member traversal path must be a real directory: "
                                + normalized + ".");
            }
            ArrayList<Path> paths = new ArrayList<>();
            try (var stream = Files.list(normalized)) {
                for (Path path : stream.toList()) {
                    if (relevant.test(path)) {
                        paths.add(path);
                    }
                }
            }
            ArrayList<String> evidence = new ArrayList<>(paths.size());
            for (Path path : paths) {
                evidence.add(directoryEntry(path));
            }
            evidence.sort(null);
            directoryEvidence.putIfAbsent(
                    new EvidenceKey(normalized, selector),
                    new WorkspaceDirectoryEvidence(normalized, selector, List.copyOf(evidence)));
            return List.copyOf(paths);
        } catch (IOException exception) {
            throw new WorkspaceConfigException(
                    "Could not enumerate workspace member directory " + normalized
                            + ". Check that it exists and is readable.");
        }
    }

    private record EvidenceKey(Path directory, String selector) {}

    WorkspaceInputs snapshot() {
        return WorkspaceInputs.captured(
                captured, missing, List.copyOf(directoryEvidence.values()));
    }

    private static String directoryEntry(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        char kind = attributes.isSymbolicLink()
                ? 'l'
                : attributes.isDirectory()
                        ? 'd'
                        : attributes.isRegularFile() ? 'f' : 'o';
        return kind + "\0" + path.getFileName();
    }

    private static String text(byte[] content) {
        return new String(content, StandardCharsets.UTF_8);
    }
}
