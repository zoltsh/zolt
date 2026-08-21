package sh.zolt.workspace.discovery;

import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.service.WorkspaceInputs;
import sh.zolt.workspace.toml.WorkspaceConfigParser;
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

/**
 * Reads each authoritative workspace config path at most once.
 */
final class WorkspaceInputCapture {
    private final Map<Path, byte[]> captured = new LinkedHashMap<>();
    private final Set<Path> missing = new LinkedHashSet<>();
    private final Map<Path, List<String>> directoryListings = new LinkedHashMap<>();

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

    Optional<WorkspaceConfigSelection> workspaceConfig(
            Path root,
            WorkspaceConfigParser parser,
            boolean required) {
        Path legacy = root.resolve(
                WorkspaceConfigParser.WORKSPACE_FILE).normalize();
        Path rootConfig = root.resolve(
                WorkspaceConfigParser.ROOT_CONFIG_FILE).normalize();
        Optional<String> legacyContent = read(legacy);
        Optional<String> rootContent = read(rootConfig);
        boolean hasLegacy = legacyContent.isPresent();
        boolean hasRootWorkspace =
                rootContent.filter(parser::hasWorkspaceSection).isPresent();
        if (hasLegacy && hasRootWorkspace) {
            throw new WorkspaceConfigException(
                    "Ambiguous workspace config at "
                            + root
                            + ". Use either zolt.toml with [workspace] or zolt-workspace.toml, not both.");
        }
        if (!hasLegacy && !hasRootWorkspace) {
            if (required) {
                throw new WorkspaceConfigException(
                        "Could not find workspace config at "
                                + root
                                + ". Add zolt.toml with [workspace] or create zolt-workspace.toml.");
            }
            return Optional.empty();
        }
        return Optional.of(hasRootWorkspace
                ? new WorkspaceConfigSelection(
                        rootConfig,
                        rootContent.orElseThrow(),
                        true)
                : new WorkspaceConfigSelection(
                        legacy,
                        legacyContent.orElseThrow(),
                        false));
    }

    List<Path> list(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        List<String> existing = directoryListings.get(normalized);
        if (existing != null) {
            return paths(normalized, existing);
        }
        try {
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceConfigException(
                        "Workspace member traversal path must be a real directory: "
                                + normalized + ".");
            }
            ArrayList<Path> paths;
            try (var stream = Files.list(normalized)) {
                paths = new ArrayList<>(stream.toList());
            }
            ArrayList<String> evidence = new ArrayList<>(paths.size());
            for (Path path : paths) {
                evidence.add(directoryEntry(path));
            }
            evidence.sort(null);
            directoryListings.put(normalized, List.copyOf(evidence));
            return List.copyOf(paths);
        } catch (IOException exception) {
            throw new WorkspaceConfigException(
                    "Could not enumerate workspace member directory " + normalized
                            + ". Check that it exists and is readable.");
        }
    }

    static Optional<Path> locate(
            Path root,
            WorkspaceConfigParser parser) {
        Path legacy = root.resolve(
                WorkspaceConfigParser.WORKSPACE_FILE).normalize();
        Path rootConfig = root.resolve(
                WorkspaceConfigParser.ROOT_CONFIG_FILE).normalize();
        boolean hasLegacy = Files.isRegularFile(legacy);
        boolean hasRootWorkspace =
                Files.isRegularFile(rootConfig)
                        && parser.hasWorkspaceSection(rootConfig);
        if (hasLegacy && hasRootWorkspace) {
            throw new WorkspaceConfigException(
                    "Ambiguous workspace config at "
                            + root
                            + ". Use either zolt.toml with [workspace] or zolt-workspace.toml, not both.");
        }
        if (hasRootWorkspace) {
            return Optional.of(rootConfig);
        }
        return hasLegacy ? Optional.of(legacy) : Optional.empty();
    }

    WorkspaceInputs snapshot() {
        return WorkspaceInputs.captured(captured, missing, directoryListings);
    }

    private static List<Path> paths(
            Path directory,
            List<String> evidence) {
        return evidence.stream()
                .map(entry -> directory.resolve(entry.substring(2)))
                .toList();
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

record WorkspaceConfigSelection(
        Path path,
        String content,
        boolean rootConfig) {
    WorkspaceConfig parse(WorkspaceConfigParser parser) {
        return rootConfig
                ? parser.parseRootConfig(content)
                : parser.parse(content);
    }
}
