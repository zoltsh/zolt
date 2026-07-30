package sh.zolt.workspace.discovery;

import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.service.WorkspaceInputs;
import sh.zolt.workspace.toml.WorkspaceConfigParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads each authoritative workspace config path at most once.
 */
final class WorkspaceInputCapture {
    private final Map<Path, byte[]> captured = new LinkedHashMap<>();
    private final Set<Path> missing = new LinkedHashSet<>();

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
        return WorkspaceInputs.captured(captured, missing);
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
