package sh.zolt.toml.manifest;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.toml.schema.ManifestField;

/** Field access and exact-path rejection for one validated generated-tool declaration. */
record ManifestGeneratedToolRow(
        ManifestDecodeIndex index,
        ManifestDecodeIndex.SectionEntry entry) {
    ManifestGeneratedToolRow {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(entry, "Generated tool section entry is required.");
    }

    Optional<ValidatedManifestField> field(ManifestField handle) {
        return index.field(entry, handle);
    }

    ValidatedManifestField required(ManifestField handle) {
        return ManifestSemanticDiagnostics.requiredField(index, entry, handle);
    }

    void reject(ManifestField... handles) {
        for (ManifestField handle : handles) {
            reject(handle, "the selected generated-tool kind does not allow this field");
        }
    }

    void reject(ManifestField handle, String reason) {
        field(handle).ifPresent(field -> ManifestSemanticDiagnostics.construct(field, () -> {
            throw new IllegalArgumentException(reason + ".");
        }));
    }
}
