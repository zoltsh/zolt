package sh.zolt.toml.manifest;

/** Final semantic-pipeline construction shared by decoder foundation tests. */
final class ManifestSemanticTestSupport {
    private ManifestSemanticTestSupport() {
    }

    static ManifestDecodeIndex index(String source) {
        ParsedManifestSyntax parsed = new TomlSyntaxParser().parse(source);
        ValidatedManifestShape shape = new ManifestShapeValidator().validate(parsed);
        return new ManifestDecodeIndex(shape);
    }
}
