package sh.zolt.manifest.effective;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.authored.AuthoredManifest;

/** Authored source domains together with their parser-independent effective project view. */
public record EffectiveManifest(
        AuthoredManifest authored,
        Optional<WorkspaceContext> workspace,
        EffectiveProject project) {
    public EffectiveManifest {
        authored = Objects.requireNonNull(authored, "Authored manifest must not be null.");
        workspace = Objects.requireNonNull(workspace, "Effective workspace context must not be null.");
        project = Objects.requireNonNull(project, "Effective project must not be null.");
        if (authored.project().isEmpty()) {
            throw new IllegalArgumentException(
                    "An effective manifest requires an authored [project] domain.");
        }
    }
}
