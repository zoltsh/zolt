package sh.zolt.toml.manifest;

import java.util.Objects;
import java.util.function.Supplier;
import org.tomlj.TomlArray;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestObjectMember;
import sh.zolt.toml.schema.ManifestPath;

/** Fail-fast semantic construction diagnostics with exact concrete paths. */
final class ManifestSemanticDiagnostics {
    private ManifestSemanticDiagnostics() {
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
