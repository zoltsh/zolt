package sh.zolt.toml.schema;

import java.util.List;
import java.util.Map;

abstract class FinalManifestSchemaTestSupport {
    final ManifestSchemaRegistry registry = FinalManifestSchema.registry();

    final List<String> sectionPaths() {
        return registry.sections().stream().map(section -> section.path().toString()).toList();
    }

    final List<String> fieldPaths() {
        return registry.fields().stream().map(field -> field.path().toString()).toList();
    }

    final ManifestSection section(String path) {
        return registry.section(path(path)).orElseThrow();
    }

    final ManifestField field(String path) {
        return registry.field(path(path)).orElseThrow();
    }

    final List<Map.Entry<String, ManifestValueKind>> fieldShapes(String section) {
        String prefix = section + ".";
        return registry.fields().stream()
                .filter(field -> field.path().toString().startsWith(prefix))
                .map(field -> Map.entry(
                        field.path().toString().substring(prefix.length()),
                        field.valueKind()))
                .toList();
    }

    final List<Integer> fieldOrders(String section) {
        String prefix = section + ".";
        return registry.fields().stream()
                .filter(field -> field.path().toString().startsWith(prefix))
                .map(ManifestField::canonicalOrder)
                .toList();
    }

    static ManifestPath path(String dotted) {
        return new ManifestPath(List.of(dotted.split("\\.")));
    }
}
