package sh.zolt.toml;

import java.util.List;
import java.util.Objects;

/**
 * The source shape of an explicit TOML table header or a table implied by a path.
 *
 * <p>An explicit {@code headerSpan} contains only the bracketed header. Its {@code bodySpan}
 * begins after the complete header line and ends at the next explicit header line or EOF. A
 * non-root implicit table has empty header and body spans at the source construct that implied it.
 * The implicit root table has an empty header at offset zero and a body ending at the first header.
 */
public record TableSyntax(
        List<String> path,
        SourceSpan headerSpan,
        SourceSpan bodySpan,
        boolean explicit,
        boolean arrayTable,
        int sourceOrder) {
    public TableSyntax {
        path = List.copyOf(Objects.requireNonNull(path, "Table path is required."));
        Objects.requireNonNull(headerSpan, "Table header span is required.");
        Objects.requireNonNull(bodySpan, "Table body span is required.");
        if (sourceOrder < 0) {
            throw new IllegalArgumentException("Table source order must not be negative.");
        }
        if (explicit && headerSpan.isEmpty()) {
            throw new IllegalArgumentException("An explicit table must have a nonempty header span.");
        }
        if (!explicit && !headerSpan.isEmpty()) {
            throw new IllegalArgumentException("An implicit table must have an empty header span.");
        }
        if (arrayTable && !explicit) {
            throw new IllegalArgumentException("An array table must be explicit.");
        }
    }

}
