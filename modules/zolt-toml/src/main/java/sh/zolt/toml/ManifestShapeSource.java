package sh.zolt.toml;

import java.util.Objects;
import java.util.Optional;

/** Best available exact source evidence for one semantic manifest node. */
record ManifestShapeSource(
        ManifestShapeOrigin origin,
        SourceSpan span,
        Optional<AssignmentSyntax> assignment,
        Optional<TableSyntax> table) {
    ManifestShapeSource {
        Objects.requireNonNull(origin, "Manifest shape origin is required.");
        Objects.requireNonNull(span, "Manifest shape source span is required.");
        assignment = Objects.requireNonNull(
                assignment, "Manifest shape assignment evidence must not be null.");
        table = Objects.requireNonNull(table, "Manifest shape table evidence must not be null.");
    }

    boolean authoredTable() {
        return origin == ManifestShapeOrigin.EXPLICIT_TABLE
                || origin == ManifestShapeOrigin.INLINE_PARENT;
    }
}

enum ManifestShapeOrigin {
    DIRECT_ASSIGNMENT,
    INLINE_PARENT,
    EXPLICIT_TABLE,
    IMPLICIT_TABLE
}
