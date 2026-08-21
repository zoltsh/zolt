package sh.zolt.workspace.service;

import sh.zolt.build.BuildException;
import sh.zolt.workspace.state.WorkspaceHash;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/** Exact workspace planning files plus non-semantic directory-listing CAS evidence. */
public final class WorkspaceInputs {
    private final Map<Path, byte[]> files;
    private final Set<Path> missing;
    private final Map<Path, List<String>> directoryListings;

    private WorkspaceInputs(
            Map<Path, byte[]> files,
            Set<Path> missing) {
        this(files, missing, Map.of());
    }

    private WorkspaceInputs(
            Map<Path, byte[]> files,
            Set<Path> missing,
            Map<Path, List<String>> directoryListings) {
        LinkedHashMap<Path, byte[]> copied = new LinkedHashMap<>();
        files.forEach((path, content) -> copied.put(normalize(path), content.clone()));
        this.files = Map.copyOf(copied);
        LinkedHashSet<Path> normalizedMissing = new LinkedHashSet<>();
        missing.forEach(path -> normalizedMissing.add(normalize(path)));
        this.missing = Set.copyOf(normalizedMissing);
        LinkedHashMap<Path, List<String>> normalizedListings = new LinkedHashMap<>();
        directoryListings.forEach((path, listing) ->
                normalizedListings.put(normalize(path), List.copyOf(listing)));
        this.directoryListings = Map.copyOf(normalizedListings);
    }

    public static WorkspaceInputs captured(
            Map<Path, byte[]> files,
            Set<Path> missing) {
        return new WorkspaceInputs(files, missing);
    }

    /** Captures file inputs plus opaque NOFOLLOW directory-entry evidence used only for CAS checks. */
    public static WorkspaceInputs captured(
            Map<Path, byte[]> files,
            Set<Path> missing,
            Map<Path, List<String>> directoryListings) {
        return new WorkspaceInputs(files, missing, directoryListings);
    }

    public static WorkspaceInputs unchecked() {
        return new WorkspaceInputs(Map.of(), Set.of());
    }

    public Optional<String> content(Path path) {
        byte[] content = files.get(normalize(path));
        return content == null
                ? Optional.empty()
                : Optional.of(new String(content, StandardCharsets.UTF_8));
    }

    public Optional<byte[]> contentBytes(Path path) {
        byte[] content = files.get(normalize(path));
        return content == null
                ? Optional.empty()
                : Optional.of(content.clone());
    }

    public WorkspaceInputs withContent(
            Path path,
            byte[] content) {
        LinkedHashMap<Path, byte[]> updated = new LinkedHashMap<>(files);
        Path normalized = normalize(path);
        updated.put(normalized, content.clone());
        LinkedHashSet<Path> updatedMissing = new LinkedHashSet<>(missing);
        updatedMissing.remove(normalized);
        return new WorkspaceInputs(updated, updatedMissing, directoryListings);
    }

    public WorkspaceInputs withMissing(Path path) {
        LinkedHashMap<Path, byte[]> updated = new LinkedHashMap<>(files);
        Path normalized = normalize(path);
        updated.remove(normalized);
        LinkedHashSet<Path> updatedMissing = new LinkedHashSet<>(missing);
        updatedMissing.add(normalized);
        return new WorkspaceInputs(updated, updatedMissing, directoryListings);
    }

    public void requireCurrent() {
        if (files.isEmpty() && missing.isEmpty() && directoryListings.isEmpty()) {
            return;
        }
        for (Map.Entry<Path, byte[]> entry : files.entrySet()) {
            if (!matches(entry.getKey(), entry.getValue())) {
                throw changed();
            }
        }
        for (Path path : missing) {
            if (Files.exists(path)) {
                throw changed();
            }
        }
        for (Map.Entry<Path, List<String>> entry : directoryListings.entrySet()) {
            List<String> current = currentDirectoryListing(entry.getKey());
            if (current == null || !current.equals(entry.getValue())) {
                throw changed();
            }
        }
    }

    Map<Path, String> digests() {
        LinkedHashMap<Path, String> values = new LinkedHashMap<>();
        files.forEach((path, content) -> values.put(path, WorkspaceHash.bytes(content)));
        missing.forEach(path -> values.put(path, "missing"));
        return Map.copyOf(values);
    }

    /** True when nothing was captured, so no digest over these inputs can be trusted. */
    public boolean isEmpty() {
        return files.isEmpty() && missing.isEmpty() && directoryListings.isEmpty();
    }

    /**
     * Digest of every captured path, keyed by its slash-separated location relative to {@code root}
     * so the result does not depend on where the workspace is checked out. Paths outside the root
     * keep their absolute form. Absent optional paths digest as {@code missing}, which is what makes
     * "a config file appeared" a change rather than a silent no-op.
     */
    public SortedMap<String, String> digestsRelativeTo(Path root) {
        Path normalizedRoot = normalize(root);
        TreeMap<String, String> values = new TreeMap<>();
        digests().forEach((path, digest) -> values.put(relative(normalizedRoot, path), digest));
        return Collections.unmodifiableSortedMap(values);
    }

    private static String relative(Path root, Path path) {
        if (!path.startsWith(root)) {
            return path.toString().replace('\\', '/');
        }
        String relative = root.relativize(path).toString().replace('\\', '/');
        return relative.isEmpty() ? "." : relative;
    }

    private static boolean matches(
            Path path,
            byte[] expected) {
        try {
            return Files.isRegularFile(path)
                    && java.util.Arrays.equals(expected, Files.readAllBytes(path));
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not verify workspace planning input " + path + ".",
                    exception);
        }
    }

    private static List<String> currentDirectoryListing(Path directory) {
        try {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            ArrayList<String> entries = new ArrayList<>();
            try (var paths = Files.list(directory)) {
                for (Path path : paths.toList()) {
                    entries.add(directoryEntry(path));
                }
            }
            entries.sort(null);
            return List.copyOf(entries);
        } catch (NoSuchFileException | NotDirectoryException exception) {
            return null;
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not verify workspace member directory " + directory + ".",
                    exception);
        }
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

    private static BuildException changed() {
        return new BuildException(
                "Workspace configuration or zolt.lock changed after planning. "
                        + "Re-plan the command under the workspace mutation lock.");
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
