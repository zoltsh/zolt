package sh.zolt.workspace.discovery;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredWorkspaceMembers;
import sh.zolt.manifest.effective.EffectiveManifestComposer;
import sh.zolt.manifest.effective.EffectiveWorkspace;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.manifest.ZoltManifestDocument;
import sh.zolt.toml.manifest.ZoltManifestParser;
import sh.zolt.workspace.WorkspaceConfigException;

/** Discovers and composes only the final {@code zolt.toml} workspace language. */
public final class ManifestWorkspaceDiscovery {
    private static final String MANIFEST = "zolt.toml";

    private final ZoltManifestParser parser;
    private final EffectiveManifestComposer composer;
    private final WorkspaceMemberExpander expander;

    public ManifestWorkspaceDiscovery() {
        this(new ZoltManifestParser(), new EffectiveManifestComposer(), new WorkspaceMemberExpander());
    }

    ManifestWorkspaceDiscovery(
            ZoltManifestParser parser,
            EffectiveManifestComposer composer,
            WorkspaceMemberExpander expander) {
        this.parser = parser;
        this.composer = composer;
        this.expander = expander;
    }

    public Optional<DiscoveredWorkspace> discover(Path start) {
        Path current = normalizedStart(start);
        while (current != null) {
            WorkspaceInputCapture capture = new WorkspaceInputCapture();
            Optional<String> source = capture.read(current.resolve(MANIFEST));
            if (source.isPresent()) {
                ZoltManifestDocument document = parse(current.resolve(MANIFEST), source.orElseThrow());
                if (document.authored().workspace().isPresent()) {
                    return Optional.of(load(current, document, capture));
                }
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    /** Non-authoritative workspace-root lookup for choosing a mutation-lock scope. */
    public Optional<Path> discoverRoot(Path start) {
        Path current = normalizedStart(start);
        while (current != null) {
            Path manifest = current.resolve(MANIFEST);
            WorkspaceInputCapture capture = new WorkspaceInputCapture();
            Optional<String> source = capture.read(manifest);
            if (source.isPresent()
                    && parse(manifest, source.orElseThrow()).authored().workspace().isPresent()) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    public DiscoveredWorkspace load(Path workspaceRoot) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        WorkspaceInputCapture capture = new WorkspaceInputCapture();
        Path manifest = root.resolve(MANIFEST);
        String source = capture.read(manifest).orElseThrow(() -> new WorkspaceConfigException(
                "Could not find final workspace manifest at " + manifest
                        + ". Add [workspace] and [workspace.members] to zolt.toml."));
        ZoltManifestDocument document = parse(manifest, source);
        if (document.authored().workspace().isEmpty()) {
            throw new WorkspaceConfigException(
                    "Manifest " + manifest + " does not declare a [workspace] domain.");
        }
        return load(root, document, capture);
    }

    private DiscoveredWorkspace load(
            Path root,
            ZoltManifestDocument rootDocument,
            WorkspaceInputCapture capture) {
        AuthoredManifest rootManifest = rootDocument.authored();
        AuthoredWorkspaceMembers membership = rootManifest.workspace()
                .orElseThrow()
                .members();
        WorkspaceMemberExpander.Expansion expansion = expander.expand(
                root, membership.include(), membership.exclude(), capture);
        LinkedHashMap<WorkspaceMemberPath, AuthoredManifest> authoredMembers = new LinkedHashMap<>();
        LinkedHashMap<WorkspaceMemberPath, DiscoveredWorkspaceMember> discoveredMembers =
                new LinkedHashMap<>();
        Set<WorkspaceMemberPattern> contributingIncludes = new LinkedHashSet<>();
        for (WorkspaceMemberExpander.Candidate candidate : expansion.candidates()) {
            Optional<ZoltManifestDocument> member = memberDocument(
                    root, rootDocument, candidate, capture);
            if (member.isEmpty()) {
                continue;
            }
            ZoltManifestDocument document = member.orElseThrow();
            authoredMembers.put(candidate.path(), document.authored());
            contributingIncludes.addAll(candidate.matchedBy());
            discoveredMembers.put(candidate.path(), new DiscoveredWorkspaceMember(
                    candidate.path(), candidate.directory(), document, candidate.matchedBy()));
        }
        for (WorkspaceMemberPattern include : membership.include()) {
            if (!contributingIncludes.contains(include)) {
                throw new WorkspaceConfigException(
                        "Workspace include `" + include
                                + "` does not contribute a final valid member.");
            }
        }

        EffectiveWorkspace effective;
        try {
            effective = composer.composeWorkspace(rootManifest, authoredMembers);
        } catch (IllegalArgumentException exception) {
            throw new WorkspaceConfigException(
                    "Invalid effective workspace at " + root.resolve(MANIFEST)
                            + ": " + exception.getMessage());
        }
        WorkspaceMemberSelection selection = selection(membership, effective);
        List<WorkspaceMemberPattern> staleExclusions = membership.exclude().stream()
                .filter(exclude -> expansion.excludedBy().getOrDefault(exclude, List.of()).isEmpty())
                .toList();
        return new DiscoveredWorkspace(
                root,
                rootDocument,
                effective,
                discoveredMembers,
                selection,
                staleExclusions,
                capture.snapshot());
    }

    private Optional<ZoltManifestDocument> memberDocument(
            Path root,
            ZoltManifestDocument rootDocument,
            WorkspaceMemberExpander.Candidate candidate,
            WorkspaceInputCapture capture) {
        if (candidate.path().value().equals(".")) {
            return Optional.of(rootDocument);
        }
        Path manifest = candidate.directory().resolve(MANIFEST);
        if (Files.isSymbolicLink(manifest)) {
            throw new WorkspaceConfigException(
                    "Workspace member `" + candidate.path()
                            + "` manifest must not be a symbolic link: " + manifest + ".");
        }
        Optional<String> source = capture.read(manifest);
        if (source.isEmpty()) {
            boolean exact = candidate.matchedBy().stream().anyMatch(pattern -> !pattern.hasWildcard());
            if (exact) {
                throw new WorkspaceConfigException(
                        "Exact workspace member `" + candidate.path()
                                + "` must contain zolt.toml at " + manifest + ".");
            }
            return Optional.empty();
        }
        return Optional.of(parse(manifest, source.orElseThrow()));
    }

    private ZoltManifestDocument parse(Path path, String source) {
        try {
            return parser.parse(source);
        } catch (ZoltConfigException exception) {
            throw new WorkspaceConfigException(
                    "Invalid workspace manifest at " + path + ": " + exception.getMessage());
        }
    }

    private static WorkspaceMemberSelection selection(
            AuthoredWorkspaceMembers membership,
            EffectiveWorkspace workspace) {
        return membership.defaultMembers()
                .map(defaults -> new WorkspaceMemberSelection(
                        WorkspaceMemberSelection.Source.EXPLICIT_DEFAULT, defaults))
                .orElseGet(() -> new WorkspaceMemberSelection(
                        WorkspaceMemberSelection.Source.IMPLICIT_ALL,
                        new ArrayList<>(workspace.members().keySet())));
    }

    private static Path normalizedStart(Path start) {
        Path current = start.toAbsolutePath().normalize();
        if (Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
            return current.getParent();
        }
        return current;
    }

}
