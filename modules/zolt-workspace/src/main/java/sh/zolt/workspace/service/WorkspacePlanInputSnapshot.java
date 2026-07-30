package sh.zolt.workspace.service;

import sh.zolt.build.BuildException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact config and lockfile bytes used to create a workspace plan. */
final class WorkspacePlanInputSnapshot {
    private final Map<Path, String> digests;

    private WorkspacePlanInputSnapshot(Map<Path, String> digests) {
        this.digests = Map.copyOf(digests);
    }

    static WorkspacePlanInputSnapshot capture(
            Workspace workspace,
            Path lockfile,
            String lockfileContent) {
        LinkedHashMap<Path, String> values = new LinkedHashMap<>();
        add(values, workspace.configPath());
        Path rootConfig = workspace.root().resolve("zolt.toml");
        if (!rootConfig.equals(workspace.configPath())) {
            add(values, rootConfig);
        }
        for (WorkspaceMember member : workspace.members()) {
            add(values, member.directory().resolve("zolt.toml"));
        }
        values.put(
                lockfile.toAbsolutePath().normalize(),
                WorkspaceHash.text(lockfileContent));
        return new WorkspacePlanInputSnapshot(values);
    }

    static WorkspacePlanInputSnapshot unchecked() {
        return new WorkspacePlanInputSnapshot(Map.of());
    }

    void requireCurrent() {
        if (digests.isEmpty() || current()) {
            return;
        }
        throw new BuildException(
                "Workspace configuration or zolt.lock changed after planning. "
                        + "Re-plan the command under the workspace mutation lock.");
    }

    private boolean current() {
        for (Map.Entry<Path, String> entry : digests.entrySet()) {
            if (!entry.getValue().equals(digest(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static void add(
            Map<Path, String> values,
            Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        values.put(normalized, digest(normalized));
    }

    private static String digest(Path path) {
        if (!Files.isRegularFile(path)) {
            return "missing";
        }
        try {
            return WorkspaceHash.bytes(Files.readAllBytes(path));
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not snapshot workspace planning input " + path + ".",
                    exception);
        }
    }
}
