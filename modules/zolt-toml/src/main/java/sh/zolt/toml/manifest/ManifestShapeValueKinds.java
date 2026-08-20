package sh.zolt.toml.manifest;

import org.tomlj.TomlArray;
import org.tomlj.TomlTable;
import sh.zolt.toml.schema.ManifestValueKind;

/** Shared raw Tomlj value-kind checks and diagnostic names. */
final class ManifestShapeValueKinds {
    private ManifestShapeValueKinds() {
    }

    static boolean matches(ManifestValueKind kind, Object value) {
        return switch (kind) {
            case STRING -> value instanceof String;
            case INTEGER -> value instanceof Long;
            case NUMBER -> value instanceof Long || value instanceof Double;
            case BOOLEAN -> value instanceof Boolean;
            case STRING_ARRAY -> stringArray(value);
            case INLINE_TABLE -> value instanceof TomlTable;
            case INLINE_TABLE_ARRAY -> tableArray(value);
            case STRING_OR_INLINE_TABLE -> value instanceof String || value instanceof TomlTable;
            case BOOLEAN_OR_STRING_ARRAY -> value instanceof Boolean || stringArray(value);
            case BOOLEAN_OR_STRING_OR_INLINE_TABLE ->
                value instanceof Boolean || value instanceof String || value instanceof TomlTable;
        };
    }

    static String expected(ManifestValueKind kind) {
        return kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    static String actual(Object value) {
        if (value instanceof String) return "string";
        if (value instanceof Long) return "integer";
        if (value instanceof Double) return "number";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof TomlArray) return "array";
        if (value instanceof TomlTable) return "table";
        return value.getClass().getSimpleName();
    }

    private static boolean stringArray(Object value) {
        if (!(value instanceof TomlArray array)) {
            return false;
        }
        for (int index = 0; index < array.size(); index++) {
            if (!(array.get(index) instanceof String)) {
                return false;
            }
        }
        return true;
    }

    private static boolean tableArray(Object value) {
        if (!(value instanceof TomlArray array)) {
            return false;
        }
        for (int index = 0; index < array.size(); index++) {
            if (!(array.get(index) instanceof TomlTable)) {
                return false;
            }
        }
        return true;
    }
}
