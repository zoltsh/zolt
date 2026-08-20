package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestValueKind;

/** Checked raw-value accessors for final-manifest decoder domains. */
final class ManifestTomlValues {
    private ManifestTomlValues() {
    }

    static String string(ValidatedManifestField field) {
        Object value = validatedRaw(field);
        if (value instanceof String string) {
            return string;
        }
        throw wrongAccessor(field, "string", value);
    }

    static long integer(ValidatedManifestField field) {
        Object value = validatedRaw(field);
        if (value instanceof Long integer) {
            return integer;
        }
        throw wrongAccessor(field, "integer", value);
    }

    static double number(ValidatedManifestField field) {
        Object value = validatedRaw(field);
        if (field.schema().descriptor().valueKind() != ManifestValueKind.NUMBER) {
            throw wrongAccessor(field, "number", value);
        }
        if (value instanceof Long integer) {
            return integer.doubleValue();
        }
        if (value instanceof Double number) {
            return number;
        }
        throw wrongAccessor(field, "number", value);
    }

    static boolean booleanValue(ValidatedManifestField field) {
        Object value = validatedRaw(field);
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw wrongAccessor(field, "boolean", value);
    }

    static List<String> strings(ValidatedManifestField field) {
        Object value = validatedRaw(field);
        if (!(value instanceof TomlArray array)) {
            throw wrongAccessor(field, "string array", value);
        }
        ArrayList<String> strings = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            Object entry = array.get(index);
            if (!(entry instanceof String string)) {
                throw wrongAccessor(field, "string array", value);
            }
            strings.add(string);
        }
        return List.copyOf(strings);
    }

    static boolean isString(ValidatedManifestField field) {
        return validatedRaw(field) instanceof String;
    }

    static boolean isBoolean(ValidatedManifestField field) {
        return validatedRaw(field) instanceof Boolean;
    }

    static boolean isInlineObject(ValidatedManifestField field) {
        return validatedRaw(field) instanceof TomlTable;
    }

    static ManifestInlineTable inlineObject(ValidatedManifestField field) {
        Object value = validatedRaw(field);
        if (!(value instanceof TomlTable)) {
            throw wrongAccessor(field, "inline object", value);
        }
        return new ManifestInlineTable(field);
    }

    static Map<String, String> stringMap(ValidatedManifestField field) {
        Object value = validatedRaw(field);
        ManifestField descriptor = field.schema().descriptor();
        if (!(value instanceof TomlTable table)
                || descriptor.valueKind() != ManifestValueKind.INLINE_TABLE
                || descriptor.objectShape().isPresent()) {
            throw wrongAccessor(field, "open string map", value);
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String key : table.keySet()) {
            Object entry = table.get(List.of(key));
            if (!(entry instanceof String string)) {
                throw new IllegalArgumentException(
                        "Manifest string map `" + field.path() + "` requires a string value for key `"
                                + key + "`; found " + ManifestShapeValueKinds.actual(entry) + ".");
            }
            values.put(key, string);
        }
        return Collections.unmodifiableMap(values);
    }

    static List<ManifestInlineTable> inlineObjectArray(ValidatedManifestField field) {
        Object value = validatedRaw(field);
        ManifestField descriptor = field.schema().descriptor();
        if (!(value instanceof TomlArray array)
                || descriptor.valueKind() != ManifestValueKind.INLINE_TABLE_ARRAY) {
            throw wrongAccessor(field, "inline object array", value);
        }
        if (descriptor.objectShape().isEmpty()) {
            throw new IllegalArgumentException(
                    "Manifest field `" + field.path()
                            + "` does not declare a closed inline-object shape.");
        }
        ArrayList<ManifestInlineTable> tables = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            tables.add(ManifestInlineTable.indexed(field, index));
        }
        return List.copyOf(tables);
    }

    private static Object validatedRaw(ValidatedManifestField field) {
        Objects.requireNonNull(field, "Validated manifest field is required.");
        ManifestField descriptor = ManifestSchemaEvidence.validatedField(field);
        Object value = field.rawValue();
        if (!ManifestShapeValueKinds.matches(descriptor.valueKind(), value)) {
            throw new IllegalStateException(
                    "Validated manifest field `" + field.path()
                            + "` has an impossible raw value kind: found "
                            + ManifestShapeValueKinds.actual(value) + ".");
        }
        return value;
    }

    private static IllegalStateException wrongAccessor(
            ValidatedManifestField field,
            String expected,
            Object value) {
        return new IllegalStateException(
                "Validated manifest field `" + field.path() + "` cannot be read as "
                        + expected + "; found " + ManifestShapeValueKinds.actual(value) + ".");
    }
}
