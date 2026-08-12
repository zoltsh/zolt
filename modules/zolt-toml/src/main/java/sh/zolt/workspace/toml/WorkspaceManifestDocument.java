package sh.zolt.workspace.toml;

import sh.zolt.workspace.WorkspaceConfig;
import java.util.Objects;

/** Captured workspace manifest source plus the exact model and parser domain it produced. */
public record WorkspaceManifestDocument(String source, WorkspaceConfig config, boolean rootConfig) {
    public WorkspaceManifestDocument {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(config, "config");
    }
}
