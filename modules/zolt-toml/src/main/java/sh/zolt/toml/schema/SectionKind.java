package sh.zolt.toml.schema;

/**
 * The structural role of a manifest section.
 *
 * <p>A collection may contain dynamic assignment fields or dynamic named-item child tables. In
 * either form an explicitly authored empty collection denotes an empty collection, never feature
 * enablement.
 */
public enum SectionKind {
    SINGLETON,
    COLLECTION,
    NAMED_ITEM
}
