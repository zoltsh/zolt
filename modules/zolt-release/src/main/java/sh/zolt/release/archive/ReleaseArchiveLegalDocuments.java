package sh.zolt.release.archive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Locates the legal documents a release archive carries next to its VERSION file.
 *
 * <p>Release archives are assembled from an application directory such as {@code apps/zolt}, while
 * these documents are kept once at the workspace root. A document that is not beside the project is
 * therefore looked up in the enclosing workspace, and the search stops at the workspace root so an
 * archive can never pick up an unrelated file from outside the project's own workspace.
 */
final class ReleaseArchiveLegalDocuments {
    static final List<String> NAMES = List.of("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES");
    private static final String WORKSPACE_TABLE = "[workspace]";
    private static final String WORKSPACE_SUBTABLE_PREFIX = "[workspace.";
    private static final String PROJECT_MANIFEST_NAME = "zolt.toml";

    private ReleaseArchiveLegalDocuments() {
    }

    static Optional<Path> resolve(Path projectDirectory, String name) {
        Path beside = projectDirectory.resolve(name);
        if (Files.isRegularFile(beside)) {
            return Optional.of(beside);
        }
        Optional<Path> workspaceRoot = workspaceRoot(projectDirectory);
        if (workspaceRoot.isEmpty()) {
            return Optional.empty();
        }
        Path current = projectDirectory.getParent();
        while (current != null) {
            Path candidate = current.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
            if (current.equals(workspaceRoot.get())) {
                return Optional.empty();
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private static Optional<Path> workspaceRoot(Path projectDirectory) {
        Path current = projectDirectory.getParent();
        while (current != null) {
            if (declaresWorkspace(current.resolve(PROJECT_MANIFEST_NAME))) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private static boolean declaresWorkspace(Path manifest) {
        if (!Files.isRegularFile(manifest)) {
            return false;
        }
        try {
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                String table = line.trim();
                if (table.equals(WORKSPACE_TABLE) || table.startsWith(WORKSPACE_SUBTABLE_PREFIX)) {
                    return true;
                }
            }
            return false;
        } catch (IOException exception) {
            return false;
        }
    }
}
