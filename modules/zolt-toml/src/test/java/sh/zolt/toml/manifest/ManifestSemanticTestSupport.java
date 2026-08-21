package sh.zolt.toml.manifest;

import sh.zolt.manifest.authored.AuthoredManifest;

/** Final semantic-pipeline construction shared by decoder foundation tests. */
public final class ManifestSemanticTestSupport {
    private ManifestSemanticTestSupport() {
    }

    static ManifestDecodeIndex index(String source) {
        ParsedManifestSyntax parsed = new TomlSyntaxParser().parse(source);
        ValidatedManifestShape shape = new ManifestShapeValidator().validate(parsed);
        return new ManifestDecodeIndex(shape);
    }

    public static AuthoredManifest decodeAuthoredManifest(String source) {
        return decodeAuthoredDocument(source).authored();
    }

    public static Decoded decodeAuthoredDocument(String source) {
        ManifestAuthoredDecoder.Decoded decoded = new ManifestAuthoredDecoder().decode(source);
        return new Decoded(decoded.source(), decoded.syntax(), decoded.authored());
    }

    public record Decoded(String source, ManifestSyntax syntax, AuthoredManifest authored) {}
}
