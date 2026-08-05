package sh.zolt.workspace.service;

import sh.zolt.build.BuildException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Exact workspace planning bytes, including files that were absent when captured.
 */
public final class WorkspaceInputs {
    private final Map<Path, byte[]> files;
    private final Set<Path> missing;

    private WorkspaceInputs(
            Map<Path, byte[]> files,
            Set<Path> missing) {
        LinkedHashMap<Path, byte[]> copied = new LinkedHashMap<>();
        files.forEach((path, content) -> copied.put(normalize(path), content.clone()));
        this.files = Map.copyOf(copied);
        LinkedHashSet<Path> normalizedMissing = new LinkedHashSet<>();
        missing.forEach(path -> normalizedMissing.add(normalize(path)));
        this.missing = Set.copyOf(normalizedMissing);
    }

    public static WorkspaceInputs captured(
            Map<Path, byte[]> files,
            Set<Path> missing) {
        return new WorkspaceInputs(files, missing);
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
        return new WorkspaceInputs(updated, updatedMissing);
    }

    public WorkspaceInputs withMissing(Path path) {
        LinkedHashMap<Path, byte[]> updated = new LinkedHashMap<>(files);
        Path normalized = normalize(path);
        updated.remove(normalized);
        LinkedHashSet<Path> updatedMissing = new LinkedHashSet<>(missing);
        updatedMissing.add(normalized);
        return new WorkspaceInputs(updated, updatedMissing);
    }

    public void requireCurrent() {
        if (files.isEmpty() && missing.isEmpty()) {
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
    }

    Map<Path, String> digests() {
        LinkedHashMap<Path, String> values = new LinkedHashMap<>();
        files.forEach((path, content) -> values.put(path, WorkspaceHash.bytes(content)));
        missing.forEach(path -> values.put(path, "missing"));
        return Map.copyOf(values);
    }

    /** True when nothing was captured, so no digest over these inputs can be trusted. */
    public boolean isEmpty() {
        return files.isEmpty() && missing.isEmpty();
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

    private static BuildException changed() {
        return new BuildException(
                "Workspace configuration or zolt.lock changed after planning. "
                        + "Re-plan the command under the workspace mutation lock.");
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
