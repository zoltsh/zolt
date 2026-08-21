package sh.zolt.workspace.discovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.unicode.Unicode17Portability;
import sh.zolt.workspace.WorkspaceConfigException;

/** Expands the final one-segment wildcard grammar without host glob or case semantics. */
final class WorkspaceMemberExpander {
    Expansion expand(
            Path root,
            List<WorkspaceMemberPattern> includes,
            List<WorkspaceMemberPattern> excludes) {
        TreeMap<WorkspaceMemberPath, MutableCandidate> candidates = new TreeMap<>();
        for (WorkspaceMemberPattern include : includes) {
            List<DirectoryCandidate> matches = expand(root, include);
            if (!include.hasWildcard() && matches.isEmpty()) {
                throw new WorkspaceConfigException(
                        "Exact workspace include `" + include
                                + "` must resolve to a directory beneath " + root + ".");
            }
            for (DirectoryCandidate match : matches) {
                MutableCandidate existing = candidates.get(match.path());
                if (existing == null) {
                    candidates.put(match.path(), new MutableCandidate(match, include));
                } else {
                    rejectNormalizationAlias(existing.candidate(), match);
                    existing.matchedBy().add(include);
                }
            }
        }
        rejectRealDirectoryAliases(candidates.values());

        LinkedHashMap<WorkspaceMemberPattern, List<WorkspaceMemberPath>> excludedBy =
                new LinkedHashMap<>();
        for (WorkspaceMemberPattern exclude : excludes) {
            ArrayList<WorkspaceMemberPath> matches = new ArrayList<>();
            candidates.keySet().stream()
                    .filter(path -> matches(exclude, path))
                    .forEach(matches::add);
            excludedBy.put(exclude, List.copyOf(matches));
        }

        ArrayList<Candidate> remaining = new ArrayList<>();
        for (MutableCandidate candidate : candidates.values()) {
            List<WorkspaceMemberPattern> matchingExcludes = excludes.stream()
                    .filter(exclude -> matches(exclude, candidate.candidate().path()))
                    .toList();
            if (!matchingExcludes.isEmpty()) {
                if (candidate.matchedBy().stream().anyMatch(include -> !include.hasWildcard())) {
                    throw new WorkspaceConfigException(
                            "Exact workspace include `" + candidate.candidate().path()
                                    + "` is removed by workspace exclude `"
                                    + matchingExcludes.getFirst() + "`.");
                }
                continue;
            }
            remaining.add(new Candidate(
                    candidate.candidate().path(),
                    candidate.candidate().directory(),
                    List.copyOf(candidate.matchedBy())));
        }
        return new Expansion(List.copyOf(remaining), Map.copyOf(excludedBy));
    }

    private static List<DirectoryCandidate> expand(
            Path root,
            WorkspaceMemberPattern pattern) {
        if (pattern.value().equals(".")) {
            return List.of(new DirectoryCandidate(
                    new WorkspaceMemberPath("."), root, "."));
        }
        List<Traversal> current = List.of(new Traversal(root, List.of(), List.of()));
        for (String segment : pattern.segments()) {
            ArrayList<Traversal> next = new ArrayList<>();
            for (Traversal parent : current) {
                List<Path> entries = entries(parent.directory());
                if (segment.equals("*")) {
                    entries.stream()
                            .filter(entry -> !entry.getFileName().toString().startsWith("."))
                            .filter(entry -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
                            .forEach(entry -> next.add(parent.child(entry)));
                } else {
                    List<Path> exact = entries.stream()
                            .filter(entry -> Unicode17Portability.normalizeNfc(
                                            entry.getFileName().toString())
                                    .equals(segment))
                            .toList();
                    if (exact.size() > 1) {
                        throw new WorkspaceConfigException(
                                "Directory entries under " + parent.directory()
                                        + " collide after Unicode NFC normalization for `" + segment + "`.");
                    }
                    if (!exact.isEmpty()) {
                        Path entry = exact.getFirst();
                        if (Files.isSymbolicLink(entry)) {
                            throw new WorkspaceConfigException(
                                    "Workspace member pattern `" + pattern
                                            + "` traverses symbolic link " + entry + ".");
                        }
                        if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                            next.add(parent.child(entry));
                        }
                    }
                }
            }
            current = deduplicated(next);
        }
        return current.stream().map(Traversal::candidate).toList();
    }

    private static List<Path> entries(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.sorted((left, right) -> compareNames(
                            left.getFileName().toString(), right.getFileName().toString()))
                    .toList();
        } catch (IOException exception) {
            throw new WorkspaceConfigException(
                    "Could not enumerate workspace member directory " + directory
                            + ". Check that it exists and is readable.");
        }
    }

    private static List<Traversal> deduplicated(List<Traversal> traversals) {
        TreeMap<WorkspaceMemberPath, Traversal> unique = new TreeMap<>();
        for (Traversal traversal : traversals) {
            DirectoryCandidate candidate = traversal.candidate();
            Traversal existing = unique.putIfAbsent(candidate.path(), traversal);
            if (existing != null) {
                rejectNormalizationAlias(existing.candidate(), candidate);
            }
        }
        return List.copyOf(unique.values());
    }

    private static void rejectNormalizationAlias(
            DirectoryCandidate existing,
            DirectoryCandidate candidate) {
        if (!existing.rawPath().equals(candidate.rawPath())) {
            throw new WorkspaceConfigException(
                    "Workspace member paths `" + existing.rawPath() + "` and `"
                            + candidate.rawPath() + "` collide after Unicode NFC normalization.");
        }
        try {
            if (!existing.directory().toRealPath().equals(candidate.directory().toRealPath())) {
                throw new WorkspaceConfigException(
                        "Workspace member path `" + candidate.path()
                                + "` resolved to different directories during discovery.");
            }
        } catch (IOException exception) {
            throw new WorkspaceConfigException(
                    "Could not verify workspace member directory " + candidate.directory() + ".");
        }
    }

    private static void rejectRealDirectoryAliases(
            Iterable<MutableCandidate> candidates) {
        Map<Path, WorkspaceMemberPath> logicalByRealDirectory = new LinkedHashMap<>();
        for (MutableCandidate candidate : candidates) {
            try {
                Path real = candidate.candidate().directory().toRealPath();
                WorkspaceMemberPath existing = logicalByRealDirectory.putIfAbsent(
                        real, candidate.candidate().path());
                if (existing != null && !existing.equals(candidate.candidate().path())) {
                    throw new WorkspaceConfigException(
                            "Workspace member paths `" + existing + "` and `"
                                    + candidate.candidate().path()
                                    + "` resolve to the same real directory " + real + ".");
                }
            } catch (IOException exception) {
                throw new WorkspaceConfigException(
                        "Could not verify workspace member directory "
                                + candidate.candidate().directory() + ".");
            }
        }
    }

    static boolean matches(
            WorkspaceMemberPattern pattern,
            WorkspaceMemberPath path) {
        if (pattern.value().equals(".") || path.value().equals(".")) {
            return pattern.value().equals(path.value());
        }
        List<String> patternSegments = pattern.segments();
        List<String> pathSegments = List.of(path.value().split("/", -1));
        if (patternSegments.size() != pathSegments.size()) {
            return false;
        }
        for (int index = 0; index < patternSegments.size(); index++) {
            String expected = patternSegments.get(index);
            String actual = pathSegments.get(index);
            if (expected.equals("*")) {
                if (actual.startsWith(".")) {
                    return false;
                }
            } else if (!expected.equals(actual)) {
                return false;
            }
        }
        return true;
    }

    private static int compareNames(String left, String right) {
        int normalized = sh.zolt.manifest.ManifestModelValues.CODE_POINT_ORDER.compare(
                Unicode17Portability.normalizeNfc(left),
                Unicode17Portability.normalizeNfc(right));
        return normalized != 0
                ? normalized
                : sh.zolt.manifest.ManifestModelValues.CODE_POINT_ORDER.compare(left, right);
    }

    record Candidate(
            WorkspaceMemberPath path,
            Path directory,
            List<WorkspaceMemberPattern> matchedBy) {}

    record Expansion(
            List<Candidate> candidates,
            Map<WorkspaceMemberPattern, List<WorkspaceMemberPath>> excludedBy) {}

    private record DirectoryCandidate(
            WorkspaceMemberPath path,
            Path directory,
            String rawPath) {}

    private record MutableCandidate(
            DirectoryCandidate candidate,
            Set<WorkspaceMemberPattern> matchedBy) {
        private MutableCandidate(
                DirectoryCandidate candidate,
                WorkspaceMemberPattern include) {
            this(candidate, new LinkedHashSet<>(List.of(include)));
        }
    }

    private record Traversal(
            Path directory,
            List<String> rawSegments,
            List<String> normalizedSegments) {
        private Traversal child(Path child) {
            String raw = child.getFileName().toString();
            ArrayList<String> nextRaw = new ArrayList<>(rawSegments);
            nextRaw.add(raw);
            ArrayList<String> nextNormalized = new ArrayList<>(normalizedSegments);
            nextNormalized.add(Unicode17Portability.normalizeNfc(raw));
            return new Traversal(child, List.copyOf(nextRaw), List.copyOf(nextNormalized));
        }

        private DirectoryCandidate candidate() {
            String normalized = String.join("/", normalizedSegments);
            return new DirectoryCandidate(
                    new WorkspaceMemberPath(normalized), directory, String.join("/", rawSegments));
        }
    }
}
