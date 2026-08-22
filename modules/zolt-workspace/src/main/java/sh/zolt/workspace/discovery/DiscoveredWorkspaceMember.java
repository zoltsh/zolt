package sh.zolt.workspace.discovery;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.toml.manifest.ZoltManifestDocument;

/** One final workspace member with its source document and complete include-pattern evidence. */
public record DiscoveredWorkspaceMember(
        WorkspaceMemberPath path,
        Path directory,
        ZoltManifestDocument document,
        List<WorkspaceMemberPattern> matchedBy) {
    public DiscoveredWorkspaceMember {
        Objects.requireNonNull(path, "Workspace member path must not be null.");
        directory = Objects.requireNonNull(
                        directory, "Workspace member directory must not be null.")
                .toAbsolutePath()
                .normalize();
        Objects.requireNonNull(document, "Workspace member document must not be null.");
        ArrayList<WorkspaceMemberPattern> sorted = new ArrayList<>(
                Objects.requireNonNull(matchedBy, "Workspace member evidence must not be null."));
        sorted.sort(null);
        for (int index = 0; index < sorted.size(); index++) {
            Objects.requireNonNull(sorted.get(index), "Workspace member evidence must not contain null.");
            if (index > 0 && sorted.get(index - 1).equals(sorted.get(index))) {
                throw new IllegalArgumentException(
                        "Workspace member evidence must not contain duplicate `" + sorted.get(index) + "`.");
            }
        }
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("Workspace member evidence must not be empty.");
        }
        matchedBy = List.copyOf(sorted);
    }

    /** Canonical workspace-relative path of this member's manifest. */
    public String manifestPath() {
        return path.value().equals(".") ? "zolt.toml" : path.value() + "/zolt.toml";
    }
}
