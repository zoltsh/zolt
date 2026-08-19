package sh.zolt.manifest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** The authored include, exclude, and optional default workspace selections. */
public record AuthoredWorkspaceMembers(
        List<WorkspaceMemberPattern> include,
        List<WorkspaceMemberPattern> exclude,
        Optional<List<WorkspaceMemberPath>> defaultMembers) {
    public AuthoredWorkspaceMembers {
        include = ManifestModelValues.sortedDistinctList(
                include, "Workspace member include");
        exclude = ManifestModelValues.sortedDistinctList(
                exclude, "Workspace member exclude");
        Objects.requireNonNull(defaultMembers, "Workspace member default selection must not be null.");
        defaultMembers = defaultMembers.map(AuthoredWorkspaceMembers::validatedDefaults);
        if (include.isEmpty()) {
            throw new IllegalArgumentException("Workspace member include must not be empty.");
        }
        if (defaultMembers.filter(List::isEmpty).isPresent()) {
            throw new IllegalArgumentException(
                    "An authored workspace default selection must not be empty.");
        }
    }

    private static List<WorkspaceMemberPath> validatedDefaults(
            List<WorkspaceMemberPath> entries) {
        List<WorkspaceMemberPath> copy = ManifestModelValues.sortedDistinctList(
                entries, "Workspace member default");
        Map<String, WorkspaceMemberPath> spellingByPortabilityKey = new HashMap<>();
        for (WorkspaceMemberPath path : copy) {
            WorkspaceMemberPath existing = spellingByPortabilityKey.putIfAbsent(
                    path.portabilityKey(), path);
            if (existing != null && !existing.equals(path)) {
                throw new IllegalArgumentException(
                        "Workspace default paths `" + existing + "` and `" + path
                                + "` collide under Unicode portability rules.");
            }
        }
        return copy;
    }
}
