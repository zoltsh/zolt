package sh.zolt.workspace.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import sh.zolt.manifest.WorkspaceMemberPath;

/** The visible root-command selection derived from explicit defaults or dynamic implicit-all. */
public record WorkspaceMemberSelection(
        Source source,
        List<WorkspaceMemberPath> members) {
    public WorkspaceMemberSelection {
        Objects.requireNonNull(source, "Workspace selection source must not be null.");
        ArrayList<WorkspaceMemberPath> sorted = new ArrayList<>(
                Objects.requireNonNull(members, "Selected workspace members must not be null."));
        sorted.sort(null);
        for (int index = 0; index < sorted.size(); index++) {
            Objects.requireNonNull(sorted.get(index), "Selected workspace members must not contain null.");
            if (index > 0 && sorted.get(index - 1).equals(sorted.get(index))) {
                throw new IllegalArgumentException(
                        "Selected workspace members must not contain duplicate `" + sorted.get(index) + "`.");
            }
        }
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("A workspace selection must not be empty.");
        }
        members = List.copyOf(sorted);
    }

    public enum Source {
        EXPLICIT_DEFAULT("explicit-default"),
        IMPLICIT_ALL("implicit-all");

        private final String value;

        Source(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
