package sh.zolt.toml;

import sh.zolt.project.ProjectConfig;
import java.util.Objects;

/**
 * A parsed {@code zolt.toml} together with the exact user-authored source that produced it.
 *
 * <p>Mutation commands must retain this document until commit time. A {@link ProjectConfig} alone
 * cannot represent comments, formatting, table order, or manifest domains owned by other codecs.
 */
public record ZoltManifestDocument(String source, ProjectConfig config) {
    public ZoltManifestDocument {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(config, "config");
    }
}
