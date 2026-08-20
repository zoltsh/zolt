package sh.zolt.toml.syntax;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable lookup indexes over the Zolt-owned manifest syntax nodes. */
public final class ManifestSourceIndex {
    private final List<TableSyntax> tables;
    private final List<AssignmentSyntax> assignments;
    private final Map<List<String>, List<TableSyntax>> tablesByPath;
    private final Map<List<String>, List<AssignmentSyntax>> assignmentsByPath;
    private final Map<List<String>, List<AssignmentSyntax>> assignmentsByTable;

    /** Builds immutable indexes from immutable Zolt syntax nodes. */
    public ManifestSourceIndex(List<TableSyntax> tables, List<AssignmentSyntax> assignments) {
        this.tables = List.copyOf(Objects.requireNonNull(tables, "Tables are required."));
        this.assignments = List.copyOf(Objects.requireNonNull(assignments, "Assignments are required."));
        tablesByPath = index(this.tables, TableSyntax::path);
        assignmentsByPath = index(this.assignments, AssignmentSyntax::fullPath);
        assignmentsByTable = index(this.assignments, AssignmentSyntax::tablePath);
    }

    public List<TableSyntax> tables() {
        return tables;
    }

    public List<AssignmentSyntax> assignments() {
        return assignments;
    }

    public List<TableSyntax> tablesAt(List<String> path) {
        return tablesByPath.getOrDefault(copyPath(path), List.of());
    }

    public List<TableSyntax> explicitTablesAt(List<String> path) {
        return tablesAt(path).stream().filter(TableSyntax::explicit).toList();
    }

    /** Assignments whose complete semantic key path equals {@code path}. */
    public List<AssignmentSyntax> assignmentsAt(List<String> path) {
        return assignmentsByPath.getOrDefault(copyPath(path), List.of());
    }

    /** Assignments authored while the given explicit or root table was current. */
    public List<AssignmentSyntax> assignmentsInTable(List<String> tablePath) {
        return assignmentsByTable.getOrDefault(copyPath(tablePath), List.of());
    }

    private static <T> Map<List<String>, List<T>> index(
            List<T> values, java.util.function.Function<T, List<String>> keyFunction) {
        LinkedHashMap<List<String>, List<T>> mutable = new LinkedHashMap<>();
        for (T value : values) {
            List<String> key = List.copyOf(keyFunction.apply(value));
            mutable.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        LinkedHashMap<List<String>, List<T>> immutable = new LinkedHashMap<>();
        mutable.forEach((key, entries) -> immutable.put(key, List.copyOf(entries)));
        return Map.copyOf(immutable);
    }

    private static List<String> copyPath(List<String> path) {
        return List.copyOf(Objects.requireNonNull(path, "Manifest path is required."));
    }
}
