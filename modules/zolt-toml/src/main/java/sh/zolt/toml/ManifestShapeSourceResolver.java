package sh.zolt.toml;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Joins Tomlj's semantic tree back to the source nodes captured by Zolt's scanner. */
final class ManifestShapeSourceResolver {
    private static final Comparator<TableSyntax> TABLE_SOURCE =
            Comparator.comparingInt(table -> table.headerSpan().start());
    private static final Comparator<AssignmentSyntax> ASSIGNMENT_SOURCE =
            Comparator.comparingInt(assignment -> assignment.keySpan().start());

    private final ManifestSourceIndex index;
    private final int sourceLength;

    ManifestShapeSourceResolver(ManifestSyntax syntax, int sourceLength) {
        this.index = syntax.sourceIndex();
        this.sourceLength = sourceLength;
    }

    ManifestShapeSource field(List<String> path) {
        Optional<AssignmentSyntax> exact = firstAssignment(path);
        if (exact.isPresent()) {
            AssignmentSyntax assignment = exact.orElseThrow();
            return assignmentSource(ManifestShapeOrigin.DIRECT_ASSIGNMENT, assignment);
        }
        Optional<AssignmentSyntax> ancestor = longestAssignmentAncestor(path);
        if (ancestor.isPresent()) {
            AssignmentSyntax assignment = ancestor.orElseThrow();
            return assignmentSource(ManifestShapeOrigin.INLINE_PARENT, assignment);
        }
        return implicit(path);
    }

    ManifestShapeSource table(List<String> path) {
        Optional<TableSyntax> explicit = index.explicitTablesAt(path).stream()
                .min(TABLE_SOURCE);
        if (explicit.isPresent()) {
            TableSyntax table = explicit.orElseThrow();
            return new ManifestShapeSource(
                    ManifestShapeOrigin.EXPLICIT_TABLE,
                    table.headerSpan(),
                    Optional.empty(),
                    Optional.of(table));
        }
        Optional<AssignmentSyntax> exact = firstAssignment(path);
        if (exact.isPresent()) {
            AssignmentSyntax assignment = exact.orElseThrow();
            return assignmentSource(ManifestShapeOrigin.INLINE_PARENT, assignment);
        }
        Optional<AssignmentSyntax> ancestor = longestAssignmentAncestor(path);
        if (ancestor.isPresent()) {
            AssignmentSyntax assignment = ancestor.orElseThrow();
            return assignmentSource(ManifestShapeOrigin.INLINE_PARENT, assignment);
        }
        return implicit(path);
    }

    private ManifestShapeSource implicit(List<String> path) {
        int offset = earliestDescendantOffset(path).orElse(0);
        return new ManifestShapeSource(
                ManifestShapeOrigin.IMPLICIT_TABLE,
                SourceSpan.emptyAt(offset),
                Optional.empty(),
                Optional.empty());
    }

    private Optional<AssignmentSyntax> firstAssignment(List<String> path) {
        return index.assignmentsAt(path).stream().min(ASSIGNMENT_SOURCE);
    }

    private Optional<AssignmentSyntax> longestAssignmentAncestor(List<String> path) {
        return index.assignments().stream()
                .filter(assignment -> isStrictPrefix(assignment.fullPath(), path))
                .max(Comparator.comparingInt((AssignmentSyntax value) -> value.fullPath().size())
                        .thenComparing(ASSIGNMENT_SOURCE.reversed()));
    }

    private Optional<Integer> earliestDescendantOffset(List<String> path) {
        return java.util.stream.Stream.concat(
                        index.assignments().stream()
                                .filter(value -> isPrefix(path, value.fullPath()))
                                .map(value -> value.keySpan().start()),
                        index.tables().stream()
                                .filter(TableSyntax::explicit)
                                .filter(value -> isPrefix(path, value.path()))
                                .map(value -> value.headerSpan().start()))
                .filter(offset -> offset >= 0 && offset <= sourceLength)
                .min(Integer::compareTo);
    }

    private static ManifestShapeSource assignmentSource(
            ManifestShapeOrigin origin,
            AssignmentSyntax assignment) {
        return new ManifestShapeSource(
                origin,
                assignment.assignmentSpan(),
                Optional.of(assignment),
                Optional.empty());
    }

    private static boolean isStrictPrefix(List<String> prefix, List<String> path) {
        return prefix.size() < path.size() && isPrefix(prefix, path);
    }

    private static boolean isPrefix(List<String> prefix, List<String> path) {
        return prefix.size() <= path.size()
                && prefix.equals(path.subList(0, prefix.size()));
    }
}
