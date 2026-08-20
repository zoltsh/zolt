package sh.zolt.toml.manifest;

import java.util.List;
import java.util.Objects;
import sh.zolt.toml.syntax.AssignmentSyntax;
import sh.zolt.toml.syntax.ManifestSourceIndex;
import sh.zolt.toml.syntax.TableSyntax;

/**
 * Immutable source-shape metadata retained alongside a parsed authored manifest.
 *
 * <p>Public callers depend only on stable, Zolt-owned syntax nodes. The package-private parsed
 * manifest wrapper retains Tomlj's semantic tree separately.
 */
public final class ManifestSyntax {
    private final List<TableSyntax> tables;
    private final List<AssignmentSyntax> assignments;
    private final ManifestSourceIndex sourceIndex;
    private final String retainedSource;

    ManifestSyntax(
            List<TableSyntax> tables,
            List<AssignmentSyntax> assignments,
            String retainedSource) {
        this.tables = List.copyOf(Objects.requireNonNull(tables, "Tables are required."));
        this.assignments = List.copyOf(Objects.requireNonNull(assignments, "Assignments are required."));
        this.sourceIndex = new ManifestSourceIndex(this.tables, this.assignments);
        this.retainedSource = Objects.requireNonNull(retainedSource, "Retained source is required.");
    }

    public List<TableSyntax> tables() {
        return tables;
    }

    public List<AssignmentSyntax> assignments() {
        return assignments;
    }

    public ManifestSourceIndex sourceIndex() {
        return sourceIndex;
    }

    boolean matchesSource(String source) {
        return retainedSource.equals(source);
    }
}
