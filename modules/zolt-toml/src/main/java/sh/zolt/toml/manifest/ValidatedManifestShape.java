package sh.zolt.toml.manifest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSchemaMatch;
import sh.zolt.toml.schema.ManifestSection;

/** Validated raw manifest nodes consumed by final semantic construction. */
record ValidatedManifestShape(
        List<ValidatedManifestSection> sections,
        List<ValidatedManifestField> fields) {
    ValidatedManifestShape {
        sections = List.copyOf(Objects.requireNonNull(sections, "Validated sections are required."));
        fields = List.copyOf(Objects.requireNonNull(fields, "Validated fields are required."));
    }
}
record ValidatedManifestSection(
        ManifestPath path,
        Optional<ManifestSchemaMatch<ManifestSection>> schema,
        ManifestShapeSource source) {
    ValidatedManifestSection {
        Objects.requireNonNull(path, "Validated section path is required.");
        schema = Objects.requireNonNull(schema, "Validated section schema must not be null.");
        Objects.requireNonNull(source, "Validated section source is required.");
    }
}

record ValidatedManifestField(
        ManifestPath path,
        ManifestSchemaMatch<ManifestField> schema,
        Object rawValue,
        ManifestShapeSource source) {
    ValidatedManifestField {
        Objects.requireNonNull(path, "Validated field path is required.");
        Objects.requireNonNull(schema, "Validated field schema is required.");
        Objects.requireNonNull(rawValue, "Validated field value is required.");
        Objects.requireNonNull(source, "Validated field source is required.");
    }
}
