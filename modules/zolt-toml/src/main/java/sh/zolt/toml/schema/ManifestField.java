package sh.zolt.toml.schema;

import java.util.Objects;

/** Schema metadata for one accepted manifest field path. */
public record ManifestField(
        ManifestPath path,
        ManifestValueKind valueKind,
        FormattingPolicy formatting,
        MutationPolicy mutation,
        int canonicalOrder) {
    public ManifestField {
        Objects.requireNonNull(path, "Manifest field path is required.");
        Objects.requireNonNull(valueKind, "Manifest field value kind is required.");
        Objects.requireNonNull(formatting, "Manifest field formatting policy is required.");
        Objects.requireNonNull(mutation, "Manifest field mutation policy is required.");
        if (canonicalOrder < 0) {
            throw new IllegalArgumentException("Manifest field canonical order must not be negative.");
        }
    }
}
