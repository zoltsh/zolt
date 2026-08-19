package sh.zolt.toml.schema;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Schema metadata for one accepted manifest section path.
 *
 * <p>For a {@link SectionKind#NAMED_ITEM} path, {@code reservedChildren} names
 * reserved values of the dynamic path segment. For a {@link SectionKind#COLLECTION},
 * it names reserved dynamic assignment keys. For a {@link SectionKind#SINGLETON},
 * it names reserved structural children beneath the section.
 */
public record ManifestSection(
        ManifestPath path,
        SectionKind kind,
        int canonicalOrder,
        Set<String> reservedChildren) {
    public ManifestSection {
        Objects.requireNonNull(path, "Manifest section path is required.");
        Objects.requireNonNull(kind, "Manifest section kind is required.");
        Objects.requireNonNull(reservedChildren, "Manifest section reserved children are required.");
        if (canonicalOrder < 0) {
            throw new IllegalArgumentException("Manifest section canonical order must not be negative.");
        }
        LinkedHashSet<String> orderedChildren = reservedChildren.stream()
                .map(ManifestPath::requireSegment)
                .sorted()
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        reservedChildren = Collections.unmodifiableSet(orderedChildren);
    }
}
