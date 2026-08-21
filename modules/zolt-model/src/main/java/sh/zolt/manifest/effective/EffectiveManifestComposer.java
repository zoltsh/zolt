package sh.zolt.manifest.effective;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredProject;
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
