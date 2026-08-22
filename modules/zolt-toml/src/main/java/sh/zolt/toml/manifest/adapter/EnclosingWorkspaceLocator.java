package sh.zolt.toml.manifest.adapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import sh.zolt.error.ActionableError;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredWorkspaceMembers;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.manifest.ZoltManifestParser;
import sh.zolt.unicode.Unicode17Portability;

/**
 * Finds the workspace a project directory belongs to, so a member is never composed standalone.
 *
 * <p>Design §4.5 "Command discovery": any command started inside a discovered workspace member
 * evaluates that member with the workspace root's shared configuration, whether or not
 * {@code --workspace} was supplied. A member manifest legally omits inherited identity, spells
 * {@code workspace = true} on a dependency, or references a root-owned alias, so composing it
 * standalone rejects manifests that are valid.
 *
 * <p>Membership follows the same one-segment wildcard grammar workspace discovery uses — the shared
 * {@link WorkspaceMemberPattern#matches} decides it — and the root project is a member only when
 * exact path {@code .} appears in {@code include} (design §4.4). This is the read path: it resolves
 * the member set a command must compose against. The alias, symlink, and stale-include validation
 * that {@code --workspace} performs stays owned by workspace discovery.
 */
final class EnclosingWorkspaceLocator {
    private static final String MANIFEST = "zolt.toml";

    private final ZoltManifestParser parser;

    EnclosingWorkspaceLocator(ZoltManifestParser parser) {
        this.parser = Objects.requireNonNull(parser, "Manifest parser is required.");
    }

    /** The workspace membership of {@code projectDirectory}, or empty for a standalone project. */
    Optional<Membership> locate(Path projectDirectory) {
        Path directory = normalized(projectDirectory);
        Path current = directory;
        while (current != null) {
            Path manifest = current.resolve(MANIFEST);
            boolean projectsOwn = current.equals(directory);
            Optional<String> source = readIfPresent(manifest);
            if (source.isPresent()) {
                Optional<AuthoredManifest> authored =
                        workspaceRoot(manifest, source.orElseThrow(), projectsOwn);
                if (authored.isPresent()) {
                    return membership(current, authored.orElseThrow(), directory);
                }
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    /**
     * The authored manifest at {@code manifest} when it declares a {@code [workspace]} domain.
     *
     * <p>The project's own manifest is parsed again by whichever composition follows, so a parse
     * failure there is left to that caller: reporting it here would prefix a plain standalone
     * diagnostic with workspace framing it does not have. An ancestor is different — nothing else
     * reads it, so an unparseable one is reported with the path that caused it.
     */
    private Optional<AuthoredManifest> workspaceRoot(
            Path manifest, String source, boolean projectsOwnManifest) {
        AuthoredManifest authored;
        try {
            authored = parser.parse(source).authored();
        } catch (ZoltConfigException exception) {
            if (projectsOwnManifest) {
                return Optional.empty();
            }
            throw new ZoltConfigException(
                    "Invalid workspace manifest at " + manifest + ": " + exception.getMessage());
        }
        return authored.workspace().isPresent() ? Optional.of(authored) : Optional.empty();
    }

    /**
     * Every member of {@code membership}'s workspace, keyed by member path. The root document is
     * reused for the {@code .} member, which effective composition requires.
     */
    Map<WorkspaceMemberPath, AuthoredManifest> members(Membership membership) {
        Objects.requireNonNull(membership, "Workspace membership is required.");
        AuthoredWorkspaceMembers declared = membership.root().workspace().orElseThrow().members();
        TreeMap<WorkspaceMemberPath, AuthoredManifest> members = new TreeMap<>();
        for (String candidate : candidates(membership.workspaceRoot(), declared)) {
            if (candidate.equals(".")) {
                members.put(new WorkspaceMemberPath("."), membership.root());
                continue;
            }
            Path manifest = membership.workspaceRoot().resolve(candidate).resolve(MANIFEST);
            readIfPresent(manifest).ifPresent(source ->
                    members.put(memberPath(candidate, manifest), parse(manifest, source)));
        }
        return members;
    }

    /**
     * The strict member identity of a manifest-bearing candidate.
     *
     * <p>Design §6.5: a directory only earns a member identity once it has a manifest, so an unrelated
     * sibling whose name cannot be a member path is skipped rather than failing every command run
     * inside the workspace. One that does carry a manifest must be renamed, because Zolt cannot
     * address it — and workspace discovery reports the same thing.
     */
    private static WorkspaceMemberPath memberPath(String candidate, Path manifest) {
        Optional<String> problem = WorkspaceMemberPath.problem(candidate);
        if (problem.isPresent()) {
            throw new ZoltConfigException(ActionableError.of(
                    "Workspace member manifest " + manifest + " has path `" + candidate
                            + "`, which is not a portable member path: " + problem.orElseThrow(),
                    "Rename the directory to a portable name, then rerun."));
        }
        return new WorkspaceMemberPath(candidate);
    }

    /**
     * The included, non-excluded raw directory paths beneath {@code workspaceRoot}.
     *
     * <p>Candidates stay raw normalized paths through exclusion so that the shared
     * {@link WorkspaceMemberPattern#matchesPath} decides membership before any strict identity exists,
     * exactly as workspace discovery does.
     */
    private static List<String> candidates(
            Path workspaceRoot, AuthoredWorkspaceMembers declared) {
        TreeMap<String, String> unique = new TreeMap<>();
        for (WorkspaceMemberPattern include : declared.include()) {
            for (String path : expand(workspaceRoot, include)) {
                unique.putIfAbsent(path, path);
            }
        }
        return unique.values().stream()
                .filter(path -> declared.exclude().stream()
                        .noneMatch(exclude -> exclude.matchesPath(path)))
                .toList();
    }

    /** Walks one include pattern over real directories, expanding each {@code *} segment. */
    private static List<String> expand(
            Path workspaceRoot, WorkspaceMemberPattern pattern) {
        if (pattern.value().equals(".")) {
            return List.of(".");
        }
        List<List<String>> current = List.of(List.of());
        for (String segment : pattern.segments()) {
            List<List<String>> next = new ArrayList<>();
            for (List<String> parent : current) {
                for (String name : childDirectories(workspaceRoot, parent, segment)) {
                    ArrayList<String> child = new ArrayList<>(parent);
                    child.add(name);
                    next.add(List.copyOf(child));
                }
            }
            current = List.copyOf(next);
        }
        return current.stream().map(segments -> String.join("/", segments)).toList();
    }

    /** The normalized child directory names one pattern segment selects under {@code parent}. */
    private static List<String> childDirectories(
            Path workspaceRoot, List<String> parent, String segment) {
        Path directory = workspaceRoot;
        for (String name : parent) {
            directory = directory.resolve(name);
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        ArrayList<String> names = new ArrayList<>();
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String name = Unicode17Portability.normalizeNfc(entry.getFileName().toString());
                boolean selected = segment.equals("*") ? !name.startsWith(".") : segment.equals(name);
                if (selected) {
                    names.add(name);
                }
            }
        } catch (IOException exception) {
            throw new ZoltConfigException(ActionableError.of(
                    "Could not enumerate workspace member directory " + directory + ".",
                    "Check that it exists and is readable.",
                    exception));
        }
        names.sort(null);
        return List.copyOf(names);
    }

    private static Optional<Membership> membership(
            Path workspaceRoot,
            AuthoredManifest root,
            Path directory) {
        Optional<WorkspaceMemberPath> path = memberPath(workspaceRoot, directory);
        if (path.isEmpty()) {
            return Optional.empty();
        }
        AuthoredWorkspaceMembers declared = root.workspace().orElseThrow().members();
        WorkspaceMemberPath member = path.orElseThrow();
        boolean included = declared.include().stream().anyMatch(include -> include.matches(member));
        boolean excluded = declared.exclude().stream().anyMatch(exclude -> exclude.matches(member));
        if (!included || excluded) {
            return Optional.empty();
        }
        return Optional.of(new Membership(workspaceRoot, root, member));
    }

    /** The root-relative member path, or empty when the directory is not beneath the root. */
    private static Optional<WorkspaceMemberPath> memberPath(Path workspaceRoot, Path directory) {
        if (workspaceRoot.equals(directory)) {
            return Optional.of(new WorkspaceMemberPath("."));
        }
        if (!directory.startsWith(workspaceRoot)) {
            return Optional.empty();
        }
        String relative = workspaceRoot.relativize(directory).toString().replace('\\', '/');
        try {
            return Optional.of(new WorkspaceMemberPath(relative));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private AuthoredManifest parse(Path manifest, String source) {
        try {
            return parser.parse(source).authored();
        } catch (ZoltConfigException exception) {
            throw new ZoltConfigException(
                    "Invalid workspace manifest at " + manifest + ": " + exception.getMessage());
        }
    }

    private static Optional<String> readIfPresent(Path manifest) {
        if (!Files.isRegularFile(manifest)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(manifest));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static Path normalized(Path projectDirectory) {
        Path directory = Objects.requireNonNull(projectDirectory, "Project directory is required.")
                .toAbsolutePath()
                .normalize();
        if (Files.isRegularFile(directory, LinkOption.NOFOLLOW_LINKS)) {
            return directory.getParent();
        }
        return directory;
    }

    /** One directory's place in the workspace that encloses it. */
    record Membership(Path workspaceRoot, AuthoredManifest root, WorkspaceMemberPath memberPath) {
        Membership {
            Objects.requireNonNull(workspaceRoot, "Workspace root directory is required.");
            Objects.requireNonNull(root, "Authored workspace root manifest is required.");
            Objects.requireNonNull(memberPath, "Workspace member path is required.");
        }
    }
}
