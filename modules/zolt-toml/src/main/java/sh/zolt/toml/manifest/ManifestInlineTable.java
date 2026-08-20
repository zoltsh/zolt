package sh.zolt.toml.manifest;

import java.util.Objects;
import java.util.Optional;
import org.tomlj.TomlTable;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestObjectMember;
import sh.zolt.toml.schema.ManifestObjectShape;
import sh.zolt.toml.schema.ManifestPath;

/** Identity-safe access to one validated closed inline object. */
final class ManifestInlineTable {
    private final ValidatedManifestField field;
    private final TomlTable table;
    private final ManifestObjectShape shape;

    ManifestInlineTable(ValidatedManifestField field) {
        this.field = Objects.requireNonNull(field, "Validated inline-object field is required.");
        ManifestField descriptor = ManifestSchemaEvidence.validatedField(field);
        this.shape = descriptor.objectShape().orElseThrow(() ->
                new IllegalArgumentException(
                        "Manifest field `" + field.path()
                                + "` does not declare a closed inline-object shape."));
        if (!(field.rawValue() instanceof TomlTable inlineTable)) {
            throw new IllegalStateException(
                    "Validated manifest field `" + field.path()
                            + "` cannot be read as an inline object; found "
                            + ManifestShapeValueKinds.actual(field.rawValue()) + ".");
        }
        this.table = inlineTable;
    }

    Optional<String> optionalString(ManifestObjectMember member) {
        ManifestObjectMember owned = requireMember(member);
        if (!table.keySet().contains(owned.name())) {
            if (owned.required()) {
                throw missingMember(owned);
            }
            return Optional.empty();
        }
        return Optional.of(stringValue(owned));
    }

    String requiredString(ManifestObjectMember member) {
        ManifestObjectMember owned = requireMember(member);
        if (!table.keySet().contains(owned.name())) {
            throw missingMember(owned);
        }
        return stringValue(owned);
    }

    ManifestPath path(ManifestObjectMember member) {
        return field.path().child(requireMember(member).name());
    }

    ManifestShapeSource source() {
        return field.source();
    }

    private ManifestObjectMember requireMember(ManifestObjectMember member) {
        Objects.requireNonNull(member, "Manifest object member handle is required.");
        return shape.members().stream()
                .filter(candidate -> candidate == member)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Inline-object access requires an exact member handle owned by `"
                                + field.path() + "`."));
    }

    private String stringValue(ManifestObjectMember member) {
        Object value = table.get(member.name());
        if (value instanceof String string) {
            return string;
        }
        throw new IllegalStateException(
                "Validated manifest field `" + path(member)
                        + "` has an impossible raw value kind: found "
                        + ManifestShapeValueKinds.actual(value) + ".");
    }

    private IllegalStateException missingMember(ManifestObjectMember member) {
        return new IllegalStateException(
                "Validated manifest field `" + path(member) + "` is unexpectedly missing.");
    }

}
