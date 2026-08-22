package sh.zolt.toml.schema;

import java.util.Optional;

/** Construction helpers shared by the final manifest field catalog. */
final class FinalManifestFieldFactory {
    private FinalManifestFieldFactory() {
    }

    static ManifestField field(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return field(
                section,
                name,
                kind,
                FormattingPolicy.DEFAULT,
                MutationPolicy.NONE,
                canonicalOrder,
                Optional.empty());
    }

    static ManifestField objectField(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder,
            ManifestObjectShape objectShape) {
        return field(
                section,
                name,
                kind,
                FormattingPolicy.DEFAULT,
                MutationPolicy.NONE,
                canonicalOrder,
                Optional.of(objectShape));
    }

    static ManifestField oneLineField(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return field(
                section,
                name,
                kind,
                FormattingPolicy.ONE_LINE,
                MutationPolicy.NONE,
                canonicalOrder,
                Optional.empty());
    }

    static ManifestField oneLineObjectField(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder,
            ManifestObjectShape objectShape) {
        return field(
                section,
                name,
                kind,
                FormattingPolicy.ONE_LINE,
                MutationPolicy.NONE,
                canonicalOrder,
                Optional.of(objectShape));
    }

    static ManifestField mutableMapEntry(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return field(
                section,
                name,
                kind,
                FormattingPolicy.ONE_LINE,
                MutationPolicy.REPLACE_ENTRY,
                canonicalOrder,
                Optional.empty());
    }

    static ManifestField mutableObjectMapEntry(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder,
            ManifestObjectShape objectShape) {
        return field(
                section,
                name,
                kind,
                FormattingPolicy.ONE_LINE,
                MutationPolicy.REPLACE_ENTRY,
                canonicalOrder,
                Optional.of(objectShape));
    }

    private static ManifestField field(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            FormattingPolicy formatting,
            MutationPolicy mutation,
            int canonicalOrder,
            Optional<ManifestObjectShape> objectShape) {
        ManifestPath path = section.child(name);
        FinalManifestFieldSemantics.Metadata semantics = FinalManifestFieldSemantics.field(path);
        return new ManifestField(
                path,
                kind,
                formatting,
                mutation,
                canonicalOrder,
                semantics.symbolFamily(),
                semantics.validation(),
                FinalManifestFieldSemantics.dynamicKeys(path),
                objectShape);
    }

    static ManifestField generatedStepField(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return field(section, name, kind, canonicalOrder);
    }
}
