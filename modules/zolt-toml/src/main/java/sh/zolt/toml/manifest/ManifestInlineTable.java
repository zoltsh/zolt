package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestObjectMember;
import sh.zolt.toml.schema.ManifestObjectShape;
import sh.zolt.toml.schema.ManifestValueKind;

/** Identity-safe access to one validated closed inline object. */
final class ManifestInlineTable {
    private final ValidatedManifestField field;
    private final TomlTable table;
    private final ManifestObjectShape shape;
    private final ManifestDiagnosticPath basePath;

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
        this.basePath = ManifestDiagnosticPath.of(field.path());
    }

    private ManifestInlineTable(
            ValidatedManifestField field,
            ManifestField descriptor,
            TomlTable table,
            int index) {
        this.field = field;
        this.shape = descriptor.objectShape().orElseThrow(() ->
                new IllegalArgumentException(
                        "Manifest field `" + field.path()
                                + "` does not declare a closed inline-object shape."));
        this.table = table;
        this.basePath = ManifestDiagnosticPath.indexed(field.path(), index);
    }

    static ManifestInlineTable indexed(ValidatedManifestField field, int index) {
        Objects.requireNonNull(field, "Validated inline-object-array field is required.");
        ManifestField descriptor = ManifestSchemaEvidence.validatedField(field);
        ManifestDiagnosticPath.indexed(field.path(), index);
        Object raw = field.rawValue();
        if (!(raw instanceof TomlArray array)
                || !ManifestShapeValueKinds.matches(descriptor.valueKind(), raw)) {
            throw impossibleArray(field, raw);
        }
        if (index >= array.size()) {
            throw new IllegalArgumentException(
                    "Manifest inline-object-array index " + index + " is out of bounds for `"
                            + field.path() + "`.");
        }
        Object item = array.get(index);
        if (!(item instanceof TomlTable table)) {
            throw impossibleArray(field, raw);
        }
        return new ManifestInlineTable(field, descriptor, table, index);
    }

    Optional<String> optionalString(ManifestObjectMember member) {
        ManifestObjectMember owned = requireKind(member, ManifestValueKind.STRING, "string");
        if (!table.keySet().contains(owned.name())) {
            if (owned.required()) {
                throw missingMember(owned);
            }
            return Optional.empty();
        }
        return Optional.of(stringValue(owned));
    }

    String requiredString(ManifestObjectMember member) {
        ManifestObjectMember owned = requireKind(member, ManifestValueKind.STRING, "string");
        if (!table.keySet().contains(owned.name())) {
            throw missingMember(owned);
        }
        return stringValue(owned);
    }

    Optional<Boolean> optionalBoolean(ManifestObjectMember member) {
        ManifestObjectMember owned = requireKind(member, ManifestValueKind.BOOLEAN, "boolean");
        if (!table.keySet().contains(owned.name())) {
            if (owned.required()) {
                throw missingMember(owned);
            }
            return Optional.empty();
        }
        Object value = table.get(owned.name());
        if (value instanceof Boolean bool) {
            return Optional.of(bool);
        }
        throw impossibleMember(owned, value);
    }

    Optional<List<String>> optionalStrings(ManifestObjectMember member) {
        ManifestObjectMember owned = requireKind(
                member, ManifestValueKind.STRING_ARRAY, "string array");
        if (!table.keySet().contains(owned.name())) {
            if (owned.required()) {
                throw missingMember(owned);
            }
            return Optional.empty();
        }
        return Optional.of(stringValues(owned));
    }

    ManifestDiagnosticPath path(ManifestObjectMember member) {
        return basePath.child(requireMember(member).name());
    }

    ManifestDiagnosticPath indexedPath(ManifestObjectMember member, int index) {
        ManifestObjectMember owned = requireKind(
                member, ManifestValueKind.STRING_ARRAY, "indexed string array");
        if (!table.keySet().contains(owned.name())) {
            throw missingMember(owned);
        }
        List<String> values = stringValues(owned);
        ManifestDiagnosticPath indexed = path(owned).indexed(index);
        if (index >= values.size()) {
            throw new IllegalArgumentException(
                    "Manifest string-array index " + index + " is out of bounds for `"
                            + path(owned) + "`.");
        }
        return indexed;
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

    private ManifestObjectMember requireKind(
            ManifestObjectMember member,
            ManifestValueKind expected,
            String accessKind) {
        ManifestObjectMember owned = requireMember(member);
        if (owned.valueKind() != expected) {
            throw new IllegalStateException(
                    "Validated manifest field `" + path(owned) + "` cannot be read as "
                            + accessKind + "; its closed-object member kind is "
                            + owned.valueKind() + ".");
        }
        return owned;
    }

    private String stringValue(ManifestObjectMember member) {
        Object value = table.get(member.name());
        if (value instanceof String string) {
            return string;
        }
        throw impossibleMember(member, value);
    }

    private List<String> stringValues(ManifestObjectMember member) {
        Object value = table.get(member.name());
        if (!(value instanceof TomlArray array)) {
            throw impossibleMember(member, value);
        }
        ArrayList<String> values = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            Object item = array.get(index);
            if (!(item instanceof String string)) {
                throw impossibleMember(member, value);
            }
            values.add(string);
        }
        return List.copyOf(values);
    }

    private IllegalStateException missingMember(ManifestObjectMember member) {
        return new IllegalStateException(
                "Validated manifest field `" + path(member) + "` is unexpectedly missing.");
    }

    private IllegalStateException impossibleMember(
            ManifestObjectMember member,
            Object value) {
        return new IllegalStateException(
                "Validated manifest field `" + path(member)
                        + "` has an impossible raw value kind: found "
                        + ManifestShapeValueKinds.actual(value) + ".");
    }

    private static IllegalStateException impossibleArray(
            ValidatedManifestField field,
            Object value) {
        return new IllegalStateException(
                "Validated manifest field `" + field.path()
                        + "` cannot provide an indexed inline object; found "
                        + ManifestShapeValueKinds.actual(value) + ".");
    }
}
