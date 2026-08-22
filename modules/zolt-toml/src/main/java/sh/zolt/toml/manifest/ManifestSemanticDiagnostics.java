package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestObjectMember;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestValueKind;

/** Fail-fast semantic construction diagnostics with exact concrete paths. */
final class ManifestSemanticDiagnostics {
    private ManifestSemanticDiagnostics() {
    }

    static <T> T constructDocument(Supplier<T> factory) {
        Objects.requireNonNull(factory, "Manifest value factory is required.");
        return constructWithContext("Invalid authored manifest", factory);
    }

    static ValidatedManifestField requiredField(
            ManifestDecodeIndex index,
            ManifestField handle) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        return index.field(handle).orElseThrow(() -> new ZoltConfigException(
                "Missing required manifest field `" + handle.path() + "`."));
    }

    static ValidatedManifestSection requiredSection(
            ManifestDecodeIndex index,
            ManifestPath handle) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        return index.section(handle).orElseThrow(() -> new ZoltConfigException(
                "Missing required manifest section `[" + handle + "]`."));
    }

    static ValidatedManifestField requiredField(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry parent,
            ManifestField handle) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        return index.field(parent, handle).orElseThrow(() -> {
            ManifestPath path = parent.section().path()
                    .child(handle.path().segments().getLast());
            return new ZoltConfigException(
                    "Missing required manifest field `" + path + "`.");
        });
    }

    /**
     * Rejects an explicitly authored empty array where omission activates the conventional default.
     *
     * <p>Design §5.5: omission is not an empty array. Filtering an authored {@code []} back onto the
     * default would invert the author's stated intent, and v1 has no "disable the default" spelling,
     * so the contradiction is reported against the exact field.
     */
    static void requireNonEmptyArray(ValidatedManifestField field, List<?> values) {
        Objects.requireNonNull(values, "Decoded manifest array is required.");
        construct(field, () -> {
            if (values.isEmpty()) {
                throw new IllegalArgumentException(
                        "an authored array must not be empty; omit the field to use the conventional "
                                + "default");
            }
            return values;
        });
    }

    static <T> T construct(
            ValidatedManifestField field,
            Supplier<T> factory) {
        Objects.requireNonNull(field, "Validated manifest field is required.");
        Objects.requireNonNull(factory, "Manifest value factory is required.");
        return construct(field.path(), factory);
    }

    static <T> T construct(
            ValidatedManifestField field,
            int index,
            Supplier<T> factory) {
        Objects.requireNonNull(field, "Validated manifest field is required.");
        Objects.requireNonNull(factory, "Manifest value factory is required.");
        ManifestField descriptor = ManifestSchemaEvidence.validatedField(field);
        Object raw = field.rawValue();
        if (!(raw instanceof TomlArray array)
                || !ManifestShapeValueKinds.matches(descriptor.valueKind(), raw)) {
            throw new IllegalStateException(
                    "Validated manifest field `" + field.path()
                            + "` cannot provide an indexed value; found "
                            + ManifestShapeValueKinds.actual(raw) + ".");
        }
        ManifestDiagnosticPath path = ManifestDiagnosticPath.indexed(field.path(), index);
        if (index >= array.size()) {
            throw new IllegalArgumentException(
                    "Manifest array index " + index + " is out of bounds for `"
                            + field.path() + "`.");
        }
        return construct(path, factory);
    }

    static <T> T construct(
            ManifestInlineTable table,
            ManifestObjectMember member,
            Supplier<T> factory) {
        Objects.requireNonNull(table, "Manifest inline object is required.");
        Objects.requireNonNull(factory, "Manifest value factory is required.");
        return construct(table.path(member), factory);
    }

    static <T> T construct(
            ManifestInlineTable table,
            ManifestObjectMember member,
            int index,
            Supplier<T> factory) {
        Objects.requireNonNull(table, "Manifest inline object is required.");
        Objects.requireNonNull(factory, "Manifest value factory is required.");
        return construct(table.indexedPath(member, index), factory);
    }

    static <T> T construct(
            ValidatedManifestSection section,
            Supplier<T> factory) {
        Objects.requireNonNull(section, "Validated manifest section is required.");
        Objects.requireNonNull(factory, "Manifest value factory is required.");
        ManifestSchemaEvidence.validatedSection(section);
        return constructWithContext(
                "Invalid manifest section `[" + section.path() + "]`", factory);
    }

    private static <T> T construct(ManifestPath path, Supplier<T> factory) {
        return construct(path.toString(), factory);
    }

    private static <T> T construct(
            ManifestDiagnosticPath path,
            Supplier<T> factory) {
        return construct(path.toString(), factory);
    }

    private static <T> T construct(String path, Supplier<T> factory) {
        return constructWithContext("Invalid value for `" + path + "`", factory);
    }

    private static <T> T constructWithContext(String context, Supplier<T> factory) {
        try {
            return factory.get();
        } catch (IllegalArgumentException failure) {
            ZoltConfigException wrapped = new ZoltConfigException(
                    context + ": " + failure.getMessage());
            wrapped.initCause(failure);
            throw wrapped;
        }
    }
}

/** Immutable display path for scalar and indexed manifest diagnostics. */
record ManifestDiagnosticPath(ManifestPath structure) {
    ManifestDiagnosticPath {
        Objects.requireNonNull(structure, "Manifest diagnostic path is required.");
    }

    static ManifestDiagnosticPath of(ManifestPath path) {
        return new ManifestDiagnosticPath(
                Objects.requireNonNull(path, "Manifest path is required."));
    }

    static ManifestDiagnosticPath indexed(ManifestPath path, int index) {
        return of(path).indexed(index);
    }

    ManifestDiagnosticPath child(String segment) {
        return new ManifestDiagnosticPath(structure.child(segment));
    }

    ManifestDiagnosticPath indexed(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Manifest diagnostic indexes must not be negative.");
        }
        ArrayList<String> segments = new ArrayList<>(structure.segments());
        int last = segments.size() - 1;
        segments.set(last, segments.get(last) + "[" + index + "]");
        return new ManifestDiagnosticPath(new ManifestPath(segments));
    }

    @Override
    public String toString() {
        return structure.toString();
    }
}

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
