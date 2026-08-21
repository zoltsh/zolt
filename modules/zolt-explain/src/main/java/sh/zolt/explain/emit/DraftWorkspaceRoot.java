package sh.zolt.explain.emit;

import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.manifest.authored.AuthoredWorkspaceMembers;
import sh.zolt.manifest.authored.AuthoredWorkspaceProjectDefaults;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the root manifest of a drafted workspace: the {@code [workspace.members]} include list and
 * the {@code [workspace.project]} identity every member inherits.
 *
 * <p>{@code default} names the exact members the audit found. Omitting it would opt the migrated
 * build into dynamic-all membership, where a future directory matching {@code include} silently joins
 * the default selection (design §6.2); that is an explicit choice, not something a migration draft
 * should make on the adopter's behalf. Shared identity is hoisted here so members stay at one
 * {@code [project]} header and one {@code name} assignment (design §5.2).
 */
final class DraftWorkspaceRoot {
    private DraftWorkspaceRoot() {
    }

    static Built build(
            String rootName,
            List<String> memberPaths,
            DraftIdentityDefaults defaults,
            List<String> notes) {
        List<WorkspaceMemberPattern> include = new ArrayList<>();
        List<String> members = new ArrayList<>();
        for (String path : memberPaths) {
            try {
                include.add(new WorkspaceMemberPattern(path));
                members.add(path);
            } catch (IllegalArgumentException exception) {
                notes.add("Module directory `" + path + "` is not a valid workspace member path: "
                        + exception.getMessage() + " Add it under [workspace.members].include by hand.");
            }
        }
        if (include.isEmpty()) {
            notes.add("No module directory could be expressed as a workspace member pattern, so the draft"
                    + " includes every top-level directory. Replace [workspace.members].include with the"
                    + " real module paths.");
            include.add(new WorkspaceMemberPattern("*"));
        }
        AuthoredManifest manifest = DraftManifests.workspaceRoot(new AuthoredWorkspace(
                DraftManifests.workspaceName(rootName, notes),
                new AuthoredWorkspaceMembers(include, List.of(), defaultSelection(members)),
                projectDefaults(defaults)));
        return new Built(manifest, members);
    }

    private static Optional<List<WorkspaceMemberPath>> defaultSelection(List<String> members) {
        return members.isEmpty()
                ? Optional.empty()
                : Optional.of(members.stream().map(WorkspaceMemberPath::new).toList());
    }

    private static Optional<AuthoredWorkspaceProjectDefaults> projectDefaults(
            DraftIdentityDefaults defaults) {
        if (defaults.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AuthoredWorkspaceProjectDefaults(
                defaults.group().map(ProjectGroup::new),
                defaults.version().map(ProjectVersion::new),
                defaults.javaRelease().map(JavaFeatureRelease::new),
                Optional.empty()));
    }

    /** The drafted root manifest and the member paths it actually includes. */
    record Built(AuthoredManifest manifest, List<String> memberPaths) {
    }

    /**
     * The shared identity to hoist. A value is shared only when every member agrees on it; a value the
     * audit could not read for some member is not shared, because inheriting it would change that
     * member's coordinates.
     */
    static DraftIdentityDefaults sharedIdentity(
            List<Optional<String>> groups,
            List<Optional<String>> versions,
            List<Optional<Integer>> javaReleases) {
        return new DraftIdentityDefaults(
                unanimous(groups).flatMap(DraftWorkspaceRoot::validGroup),
                unanimous(versions).flatMap(DraftWorkspaceRoot::validVersion),
                unanimous(javaReleases));
    }

    private static <T> Optional<T> unanimous(List<Optional<T>> values) {
        if (values.isEmpty()) {
            return Optional.empty();
        }
        Optional<T> first = values.getFirst();
        if (first.isEmpty()) {
            return Optional.empty();
        }
        for (Optional<T> value : values) {
            if (!first.equals(value)) {
                return Optional.empty();
            }
        }
        return first;
    }

    private static Optional<String> validGroup(String value) {
        try {
            new ProjectGroup(value);
            return Optional.of(value);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Optional<String> validVersion(String value) {
        try {
            new ProjectVersion(value);
            return Optional.of(value);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
