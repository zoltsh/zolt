package sh.zolt.toml;

import java.util.List;
import java.util.Objects;
import org.tomlj.TomlParseResult;

/**
 * Immutable source-shape metadata retained alongside a parsed authored manifest.
 *
 * <p>The underlying Tomlj result stays package-private so public callers depend only on stable,
 * Zolt-owned syntax nodes.
 */
public final class ManifestSyntax {
    private final List<TableSyntax> tables;
    private final List<AssignmentSyntax> assignments;
    private final ManifestSourceIndex sourceIndex;
    private final TomlParseResult parsed;
    private final String retainedSource;

    ManifestSyntax(
            List<TableSyntax> tables,
            List<AssignmentSyntax> assignments,
            TomlParseResult parsed,
            String retainedSource) {
        this.tables = List.copyOf(Objects.requireNonNull(tables, "Tables are required."));
        this.assignments = List.copyOf(Objects.requireNonNull(assignments, "Assignments are required."));
        this.sourceIndex = new ManifestSourceIndex(this.tables, this.assignments);
        this.parsed = Objects.requireNonNull(parsed, "Parsed TOML is required.");
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

    TomlParseResult parsed() {
        return parsed;
    }

    boolean matchesSource(String source) {
        return retainedSource.equals(source);
    }
}
