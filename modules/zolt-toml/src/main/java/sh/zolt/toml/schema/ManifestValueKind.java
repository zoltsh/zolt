package sh.zolt.toml.schema;

/** The accepted source shape for a manifest field. */
public enum ManifestValueKind {
    STRING,
    INTEGER,
    BOOLEAN,
    STRING_ARRAY,
    INLINE_TABLE,
    INLINE_TABLE_ARRAY,
    STRING_OR_INLINE_TABLE
}
