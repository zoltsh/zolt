package sh.zolt.publish;

import java.util.LinkedHashMap;
import java.util.Map;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.adapter.EffectiveProjectConfigAdapter;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.effective.EffectiveManifestComposer;
import sh.zolt.manifest.effective.EffectiveWorkspace;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/**
 * Composes final-language manifests into the legacy {@link ProjectConfig} the publish engine
 * consumes. A {@code workspace = true} dependency resolves by effective member identity (design
 * §9.8), so a consumer that declares one is composed against its providers rather than standalone.
 */
final class PublishManifestFixtures {
    private static final ManifestProjectConfigLoader LOADER = new ManifestProjectConfigLoader();

    private PublishManifestFixtures() {
    }

    static ProjectConfig standalone(String manifest) {
        return LOADER.load(manifest);
    }

    /**
     * Composes {@code consumer} as the {@code app} member of a workspace whose remaining members are
     * {@code providers}, keyed by member path, and returns the consumer's effective configuration.
     */
    static ProjectConfig workspaceMember(String consumer, Map<String, String> providers) {
        Map<WorkspaceMemberPath, AuthoredManifest> members = new LinkedHashMap<>();
        members.put(new WorkspaceMemberPath("app"), LOADER.document(consumer).authored());
        providers.forEach((path, manifest) ->
                members.put(new WorkspaceMemberPath(path), LOADER.document(manifest).authored()));
        AuthoredManifest root = LOADER.document("""
                [workspace]
                name = "publish-fixtures"

                [workspace.members]
                include = ["app"]
                """).authored();
        EffectiveWorkspace workspace = new EffectiveManifestComposer().composeWorkspace(root, members);
        WorkspaceMemberPath app = new WorkspaceMemberPath("app");
        return new EffectiveProjectConfigAdapter().adapt(
                workspace.members().get(app),
                EffectiveProjectConfigAdapter.workspacePaths(workspace, app));
    }
}
