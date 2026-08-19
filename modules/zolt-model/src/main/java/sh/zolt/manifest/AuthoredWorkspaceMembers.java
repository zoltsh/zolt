package sh.zolt.manifest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The authored include, exclude, and optional default workspace selections. */
public record AuthoredWorkspaceMembers(
        List<String> include,
        List<String> exclude,
        Optional<List<String>> defaultMembers) {
    public AuthoredWorkspaceMembers {
        include = copyEntries(include, "Workspace member include");
        exclude = copyEntries(exclude, "Workspace member exclude");
        Objects.requireNonNull(defaultMembers, "Workspace member default selection must not be null.");
        defaultMembers = defaultMembers.map(entries -> copyEntries(entries, "Workspace member default"));
        if (include.isEmpty()) {
            throw new IllegalArgumentException("Workspace member include must not be empty.");
        }
    }

    private static List<String> copyEntries(List<String> entries, String subject) {
        Objects.requireNonNull(entries, subject + " must not be null.");
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                throw new IllegalArgumentException(subject + " entries must not be blank.");
            }
        }
        return List.copyOf(entries);
    }
}
