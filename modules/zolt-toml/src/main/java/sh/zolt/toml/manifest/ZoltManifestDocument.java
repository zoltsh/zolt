package sh.zolt.toml.manifest;

import java.util.Objects;
import sh.zolt.manifest.authored.AuthoredManifest;

/** Exact source, syntax evidence, and parser-independent authored manifest. */
public record ZoltManifestDocument(String source, ManifestSyntax syntax, AuthoredManifest authored) {
    public ZoltManifestDocument {
        Objects.requireNonNull(source, "Manifest source is required.");
        Objects.requireNonNull(syntax, "Manifest syntax is required.");
        Objects.requireNonNull(authored, "Authored manifest is required.");
        if (!syntax.matchesSource(source)) {
            throw new IllegalArgumentException("Manifest syntax must match the retained source.");
        }
    }
}
