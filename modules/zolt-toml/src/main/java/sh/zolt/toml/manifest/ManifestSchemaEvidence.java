package sh.zolt.toml.manifest;

import java.util.Objects;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSchemaMatch;
import sh.zolt.toml.schema.ManifestSchemaRegistry;
import sh.zolt.toml.schema.ManifestSection;

/** Exact final-registry identity checks for validated fields, sections, and handles. */
final class ManifestSchemaEvidence {
    private static final ManifestSchemaRegistry REGISTRY = FinalManifestSchema.registry();

    private ManifestSchemaEvidence() {
    }

    static ManifestField validatedField(ValidatedManifestField field) {
        Objects.requireNonNull(field, "Validated manifest field is required.");
        ManifestField descriptor = field.schema().descriptor();
        ManifestField registered = REGISTRY.field(descriptor.path()).orElseThrow(() ->
                new IllegalStateException(
                        "Validated manifest field uses an unregistered descriptor `"
                                + descriptor.path() + "`."));
        ManifestSchemaMatch<ManifestField> rematched =
                REGISTRY.matchField(field.path()).orElseThrow(() ->
                        new IllegalStateException(
                                "Validated manifest field `" + field.path()
                                        + "` does not match the final schema."));
        if (registered != descriptor
                || rematched.descriptor() != descriptor
                || !rematched.bindings().equals(field.schema().bindings())) {
            throw new IllegalStateException(
                    "Validated manifest field `" + field.path()
                            + "` does not use its exact registered schema match.");
        }
        return descriptor;
    }

    static ManifestSection validatedSection(ValidatedManifestSection section) {
        Objects.requireNonNull(section, "Validated manifest section is required.");
        ManifestSection descriptor = section.schema()
                .orElseThrow(() -> new IllegalStateException(
                        "Validated manifest section `" + section.path()
                                + "` has no registered schema match."))
                .descriptor();
        ManifestSection registered = REGISTRY.section(descriptor.path()).orElseThrow(() ->
                new IllegalStateException(
                        "Validated manifest section uses an unregistered descriptor `"
                                + descriptor.path() + "`."));
        ManifestSchemaMatch<ManifestSection> rematched =
                REGISTRY.matchSection(section.path()).orElseThrow(() ->
                        new IllegalStateException(
                                "Validated manifest section `" + section.path()
                                        + "` does not match the final schema."));
        if (registered != descriptor
                || rematched.descriptor() != descriptor
                || !rematched.bindings().equals(section.schema().orElseThrow().bindings())) {
            throw new IllegalStateException(
                    "Validated manifest section `" + section.path()
                            + "` does not use its exact registered schema match.");
        }
        return descriptor;
    }

    static ManifestField fieldHandle(ManifestField handle) {
        Objects.requireNonNull(handle, "Manifest field handle is required.");
        ManifestField registered = REGISTRY.field(handle.path()).orElseThrow(() ->
                new IllegalArgumentException(
                        "Manifest field handle `" + handle.path() + "` is not registered."));
        if (registered != handle) {
            throw new IllegalArgumentException(
                    "Manifest field access requires the exact registered handle `"
                            + handle.path() + "`.");
        }
        return registered;
    }

    static ManifestSection sectionHandle(ManifestPath handle) {
        Objects.requireNonNull(handle, "Manifest section handle is required.");
        ManifestSection registered = REGISTRY.section(handle).orElseThrow(() ->
                new IllegalArgumentException(
                        "Manifest section handle `[" + handle + "]` is not registered."));
        if (registered.path() != handle) {
            throw new IllegalArgumentException(
                    "Manifest section access requires the exact registered path `["
                            + handle + "]`.");
        }
        return registered;
    }

    static boolean hasRegisteredSection(ManifestPath path) {
        return REGISTRY.matchSection(path).isPresent();
    }
}
