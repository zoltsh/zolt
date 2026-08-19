package sh.zolt.toml.schema;

/** The source-preserving mutation behavior supported for a manifest field. */
public enum MutationPolicy {
    NONE,
    REPLACE_VALUE,
    REPLACE_ENTRY
}
