package sh.zolt.toml.schema;

/** Construction helpers shared by the final manifest field catalog. */
final class FinalManifestFieldFactory {
    private FinalManifestFieldFactory() {
    }

    static ManifestField field(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        ManifestPath path = section.child(name);
        FinalManifestFieldSemantics.Metadata semantics = FinalManifestFieldSemantics.field(path);
        return new ManifestField(
                path,
                kind,
                FormattingPolicy.DEFAULT,
                MutationPolicy.NONE,
                canonicalOrder,
                semantics.symbolFamily(),
                semantics.validation(),
                FinalManifestFieldSemantics.dynamicKeys(path));
    }

    static ManifestField oneLineField(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        ManifestPath path = section.child(name);
        FinalManifestFieldSemantics.Metadata semantics = FinalManifestFieldSemantics.field(path);
        return new ManifestField(
                path,
                kind,
                FormattingPolicy.ONE_LINE,
                MutationPolicy.NONE,
                canonicalOrder,
                semantics.symbolFamily(),
                semantics.validation(),
                FinalManifestFieldSemantics.dynamicKeys(path));
    }

    static ManifestField mutableMapEntry(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        ManifestPath path = section.child(name);
        FinalManifestFieldSemantics.Metadata semantics = FinalManifestFieldSemantics.field(path);
        return new ManifestField(
                path,
                kind,
                FormattingPolicy.ONE_LINE,
                MutationPolicy.REPLACE_ENTRY,
                canonicalOrder,
                semantics.symbolFamily(),
                semantics.validation(),
                FinalManifestFieldSemantics.dynamicKeys(path));
    }

    static ManifestField generatedStepField(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return field(section, name, kind, canonicalOrder);
    }
}
