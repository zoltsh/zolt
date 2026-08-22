package sh.zolt.manifest.effective;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.ManifestSource;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredProject;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.manifest.authored.AuthoredWorkspaceProjectDefaults;
import sh.zolt.manifest.authored.ProjectLocalDomains;

/** Public pure-model entrypoint for composing authored manifests into effective project views. */
public final class EffectiveManifestComposer {
    private static final String STANDALONE_MANIFEST_PATH = "zolt.toml";

    private final StandaloneManifestIntegrityValidator integrity =
            new StandaloneManifestIntegrityValidator();
    private final EffectiveProjectIdentityComposer identities =
            new EffectiveProjectIdentityComposer();
    private final EffectiveStandaloneSharedComposer shared =
            new EffectiveStandaloneSharedComposer();
    private final EffectiveWorkspaceSharedComposer workspaceShared =
            new EffectiveWorkspaceSharedComposer();

    /** Composes one standalone project or BOM and records authored provenance from {@code zolt.toml}. */
    public EffectiveManifest composeStandalone(AuthoredManifest authored) {
        Objects.requireNonNull(authored, "Authored manifest must not be null.");
        if (authored.workspace().isPresent()) {
            throw new IllegalArgumentException(
                    "Standalone effective composition does not accept a [workspace] domain.");
        }
        AuthoredProject project = authored.project().orElseThrow(() ->
                new IllegalArgumentException(
                        "Standalone effective composition requires a [project] domain."));
        boolean bom = authored.packaging().bom().isPresent();
        EffectiveProjectIdentity identity = identities.compose(
                project.identity(), STANDALONE_MANIFEST_PATH, Optional.empty(), bom);
        integrity.validate(authored);
        EffectiveSharedConfiguration sharedConfiguration =
                shared.compose(authored, identity, STANDALONE_MANIFEST_PATH, bom);
        EffectiveProject effectiveProject = new EffectiveProject(
                identity, sharedConfiguration, localDomains(authored, project));
        return new EffectiveManifest(authored, Optional.empty(), effectiveProject);
    }

    /**
     * Composes one decoded workspace root and its complete final member map.
     *
     * <p>The caller owns filesystem discovery and supplies the already expanded, excluded, and
     * validated final member set. Composition validates member/document shape and explicit default
     * selection against that set.
     *
     * <p>Workspace dependency-selector edges and BOM member selections remain in each member's
     * project-local authored domains. They are intentionally resolved only after this method has
     * produced the complete effective identity index for the graph.
     */
    public EffectiveWorkspace composeWorkspace(
            AuthoredManifest root,
            Map<WorkspaceMemberPath, AuthoredManifest> finalMembers) {
        Objects.requireNonNull(root, "Authored workspace root must not be null.");
        AuthoredWorkspace workspace = root.workspace().orElseThrow(() ->
                new IllegalArgumentException("Workspace composition requires a [workspace] root domain."));
        Map<WorkspaceMemberPath, AuthoredManifest> members = immutableMembers(finalMembers);
        validateMemberSet(root, workspace, members);
        integrity.validateWorkspaceRoot(root);

        Map<WorkspaceMemberPath, EffectiveManifest> effective = new LinkedHashMap<>();
        for (Map.Entry<WorkspaceMemberPath, AuthoredManifest> entry : members.entrySet()) {
            effective.put(
                    entry.getKey(),
                    composeMember(root, workspace, entry.getKey(), entry.getValue()));
        }
        return new EffectiveWorkspace(root, effective);
    }

    /**
     * Composes one workspace member against its authored root without expanding the rest of the
     * member set.
     *
     * <p>Design §4.5 "Command discovery": a directory a workspace expanded into a member is always
     * evaluated with the workspace root's shared configuration. A reader that needs exactly one
     * member's effective view uses this rather than {@link #composeStandalone(AuthoredManifest)},
     * which both drops the root's shared domains and rejects member-only spellings such as
     * {@code workspace = true}.
     */
    public EffectiveManifest composeWorkspaceMember(
            AuthoredManifest root,
            WorkspaceMemberPath path,
            AuthoredManifest member) {
        Objects.requireNonNull(root, "Authored workspace root must not be null.");
        Objects.requireNonNull(path, "Workspace member path must not be null.");
        Objects.requireNonNull(member, "Authored workspace member must not be null.");
        AuthoredWorkspace workspace = root.workspace().orElseThrow(() ->
                new IllegalArgumentException("Workspace composition requires a [workspace] root domain."));
        validateMemberManifest(root, path, member);
        return composeMember(root, workspace, path, member);
    }

    private EffectiveManifest composeMember(
            AuthoredManifest root,
            AuthoredWorkspace workspace,
            WorkspaceMemberPath path,
            AuthoredManifest member) {
        String memberManifestPath = manifestPath(path);
        AuthoredProject authoredProject = member.project().orElseThrow();
        boolean bom = member.packaging().bom().isPresent();
        Optional<EffectiveProjectIdentityComposer.WorkspaceDefaults> defaults =
                workspace.projectDefaults().map(value -> workspaceDefaults(value));
        EffectiveProjectIdentity identity = identities.compose(
                authoredProject.identity(), memberManifestPath, defaults, bom);
        boolean rootMember = path.value().equals(".");
        EffectiveSharedConfiguration sharedConfiguration = rootMember
                ? shared.compose(root, identity, STANDALONE_MANIFEST_PATH, bom)
                : workspaceShared.compose(
                        root,
                        member,
                        identity,
                        STANDALONE_MANIFEST_PATH,
                        memberManifestPath,
                        bom);
        EffectiveProject project = new EffectiveProject(
                identity, sharedConfiguration, localDomains(member, authoredProject));
        integrity.validateWorkspaceMember(member, sharedConfiguration);
        EffectiveValue<LocalId> workspaceName = rootMember
                ? EffectiveValue.authored(
                        workspace.name(), source("workspace", "name"))
                : EffectiveValue.inherited(
                        workspace.name(), source("workspace", "name"));
        return new EffectiveManifest(
                member,
                Optional.of(new WorkspaceContext(workspaceName, path)),
                project);
    }

    private static Map<WorkspaceMemberPath, AuthoredManifest> immutableMembers(
            Map<WorkspaceMemberPath, AuthoredManifest> members) {
        return ManifestModelValues.immutableSortedMap(
                Objects.requireNonNull(members, "Final workspace members must not be null."),
                WorkspaceMemberPath::compareTo,
                "Final workspace member path",
                "Final workspace member manifest");
    }

    private static void validateMemberSet(
            AuthoredManifest root,
            AuthoredWorkspace workspace,
            Map<WorkspaceMemberPath, AuthoredManifest> members) {
        if (members.isEmpty()) {
            throw new IllegalArgumentException("A workspace must contain at least one final member.");
        }
        for (Map.Entry<WorkspaceMemberPath, AuthoredManifest> entry : members.entrySet()) {
            WorkspaceMemberPath path = entry.getKey();
            AuthoredManifest member = entry.getValue();
            validateMemberManifest(root, path, member);
        }
        workspace.members().defaultMembers().ifPresent(defaults -> defaults.forEach(path -> {
            if (!members.containsKey(path)) {
                throw new IllegalArgumentException(
                        "Workspace default member `" + path
                                + "` is not in the final member set.");
            }
        }));
        rejectPortablePathCollisions(members.keySet());
    }

    private static void validateMemberManifest(
            AuthoredManifest root,
            WorkspaceMemberPath path,
            AuthoredManifest member) {
        if (path.value().equals(".")) {
            if (member != root) {
                throw new IllegalArgumentException(
                        "The `.` workspace member must reuse the authored workspace root instance.");
            }
            if (root.project().isEmpty()) {
                throw new IllegalArgumentException(
                        "The `.` workspace member requires a root [project] domain.");
            }
            return;
        }
        if (member == root) {
            throw new IllegalArgumentException(
                    "Only the `.` workspace member may reuse the authored workspace root.");
        }
        if (member.workspace().isPresent()) {
            throw new IllegalArgumentException(
                    "Workspace member `" + path + "` cannot declare a nested [workspace] domain.");
        }
        if (member.project().isEmpty()) {
            throw new IllegalArgumentException(
                    "Workspace member `" + path + "` requires a [project] domain.");
        }
    }

    private static void rejectPortablePathCollisions(Iterable<WorkspaceMemberPath> paths) {
        Map<String, WorkspaceMemberPath> spellingByKey = new HashMap<>();
        for (WorkspaceMemberPath path : paths) {
            WorkspaceMemberPath existing = spellingByKey.putIfAbsent(path.portabilityKey(), path);
            if (existing != null && !existing.equals(path)) {
                throw new IllegalArgumentException(
                        "Workspace member paths `" + existing + "` and `" + path
                                + "` collide under Unicode portability rules.");
            }
        }
    }

    private static EffectiveProjectIdentityComposer.WorkspaceDefaults workspaceDefaults(
            AuthoredWorkspaceProjectDefaults defaults) {
        return new EffectiveProjectIdentityComposer.WorkspaceDefaults(
                defaults, STANDALONE_MANIFEST_PATH);
    }

    private static String manifestPath(WorkspaceMemberPath path) {
        return path.value().equals(".")
                ? STANDALONE_MANIFEST_PATH
                : path.value() + "/zolt.toml";
    }

    private static ManifestSource source(String... path) {
        return new ManifestSource(STANDALONE_MANIFEST_PATH, List.of(path));
    }

    private static ProjectLocalDomains localDomains(
            AuthoredManifest authored,
            AuthoredProject project) {
        return new ProjectLocalDomains(
                project.metadata(),
                authored.dependencies(),
                authored.dependencyConstraints(),
                authored.dependencyPolicy(),
                authored.build().build(),
                authored.build().compiler(),
                authored.build().resources(),
                authored.build().tests(),
                authored.generated(),
                authored.packaging(),
                authored.publishing());
    }
}
