package sh.zolt.toml.manifest.edit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import sh.zolt.toml.manifest.ZoltManifestDocument;
import sh.zolt.toml.schema.FinalManifestDependencyFields;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSchemaRegistry;
import sh.zolt.toml.schema.ManifestSection;
import sh.zolt.toml.schema.MutationPolicy;
import sh.zolt.toml.syntax.AssignmentSyntax;
import sh.zolt.toml.syntax.TableSyntax;

/** Computes exact source edits for schema-declared mutable entries. */
final class ManifestSourcePatch {
    private static final ManifestSchemaRegistry SCHEMA = FinalManifestSchema.registry();
    private static final Set<ManifestField> DEPENDENCY_LANES = Set.of(
            FinalManifestDependencyFields.DEPENDENCIES_ENTRY,
            FinalManifestDependencyFields.DEPENDENCIES_API_ENTRY,
            FinalManifestDependencyFields.DEPENDENCIES_RUNTIME_ENTRY,
            FinalManifestDependencyFields.DEPENDENCIES_PROVIDED_ENTRY,
            FinalManifestDependencyFields.DEPENDENCIES_DEV_ENTRY,
            FinalManifestDependencyFields.DEPENDENCIES_TEST_ENTRY,
            FinalManifestDependencyFields.DEPENDENCIES_PROCESSOR_ENTRY,
            FinalManifestDependencyFields.DEPENDENCIES_TEST_PROCESSOR_ENTRY);

    private final ZoltManifestDocument original;
    private final Map<List<String>, CanonicalEntry> before;
    private final Map<List<String>, CanonicalEntry> after;
    private final String newline;
    private final boolean mixedNewlines;
    private final List<TextEdit> edits = new ArrayList<>();
    private final Map<Integer, StringBuilder> insertions = new LinkedHashMap<>();

    ManifestSourcePatch(
            ZoltManifestDocument original,
            ZoltManifestDocument canonicalBefore,
            ZoltManifestDocument canonicalAfter) {
        this.original = Objects.requireNonNull(original, "Original document is required.");
        before = mutableEntries(canonicalBefore);
        after = mutableEntries(canonicalAfter);
        newline = original.source().contains("\r\n") ? "\r\n" : "\n";
        mixedNewlines = hasMixedNewlines(original.source());
    }

    String apply() {
        Map<List<String>, AssignmentSyntax> removed = new LinkedHashMap<>();
        for (CanonicalEntry existing : before.values()) {
            CanonicalEntry desired = after.get(existing.path());
            if (desired != null && desired.value().equals(existing.value())) {
                continue;
            }
            AssignmentSyntax source = sourceEntry(existing.path());
            if (desired == null) {
                removed.put(existing.path(), source);
                edits.add(new TextEdit(source.lineSpan().start(), source.lineSpan().end(), ""));
            } else {
                edits.add(new TextEdit(
                        source.valueSpan().start(),
                        source.valueSpan().end(),
                        desired.value()));
            }
        }

        Map<List<String>, List<Addition>> additions = new LinkedHashMap<>();
        for (CanonicalEntry desired : after.values()) {
            if (before.containsKey(desired.path())) {
                continue;
            }
            String suffix = movedCommentSuffix(desired, removed);
            additions.computeIfAbsent(desired.parent(), ignored -> new ArrayList<>())
                    .add(new Addition(desired.assignment(), suffix));
        }
        if (!additions.isEmpty() && mixedNewlines) {
            throw unsafe("the captured source mixes LF and CRLF line endings");
        }
        additions.forEach(this::insert);

        insertions.forEach((offset, text) ->
                edits.add(new TextEdit(offset, offset, text.toString())));
        return applyEdits();
    }

    private String movedCommentSuffix(
            CanonicalEntry addition,
            Map<List<String>, AssignmentSyntax> removed) {
        if (!DEPENDENCY_LANES.contains(addition.field())) {
            return "";
        }
        List<Map.Entry<List<String>, AssignmentSyntax>> candidates = removed.entrySet().stream()
                .filter(entry -> entry.getKey().getLast().equals(addition.path().getLast()))
                .filter(entry -> DEPENDENCY_LANES.contains(before.get(entry.getKey()).field()))
                .toList();
        if (candidates.size() != 1) {
            return "";
        }
        AssignmentSyntax source = candidates.getFirst().getValue();
        if (source.trailingCommentSpan().isEmpty()) {
            return "";
        }
        int end = source.lineSpan().end();
        String text = original.source();
        if (end > source.valueSpan().end() && text.charAt(end - 1) == '\n') {
            end--;
        }
        if (end > source.valueSpan().end() && text.charAt(end - 1) == '\r') {
            end--;
        }
        return text.substring(source.valueSpan().end(), end);
    }

    private void insert(List<String> tablePath, List<Addition> additions) {
        List<TableSyntax> tables = original.syntax().sourceIndex().explicitTablesAt(tablePath);
        if (tables.size() > 1) {
            throw unsafe("mutable table [" + String.join(".", tablePath)
                    + "] does not have one explicit header");
        }
        String assignments = assignments(additions);
        if (tables.size() == 1) {
            int offset = existingTableInsertion(tables.getFirst(), tablePath);
            StringBuilder insertion = insertions.computeIfAbsent(offset, ignored -> new StringBuilder());
            if (insertion.isEmpty() && needsLineBreakBefore(offset)) {
                insertion.append(newline);
            }
            insertion.append(assignments);
            return;
        }

        int offset = schemaBoundary(tablePath);
        StringBuilder insertion = insertions.computeIfAbsent(offset, ignored -> new StringBuilder());
        appendSeparatedTable(insertion, offset, tablePath, assignments);
    }

    private int existingTableInsertion(TableSyntax table, List<String> tablePath) {
        return original.syntax().sourceIndex().assignmentsInTable(tablePath).stream()
                .map(AssignmentSyntax::lineSpan)
                .mapToInt(span -> span.end())
                .max()
                .orElse(table.bodySpan().start());
    }

    private int schemaBoundary(List<String> tablePath) {
        ManifestSection target = SCHEMA.section(new ManifestPath(tablePath))
                .orElseThrow(() -> unsafe("mutable table schema metadata is unavailable"));
        List<TableOrder> tables = original.syntax().tables().stream()
                .filter(TableSyntax::explicit)
                .map(table -> new TableOrder(table, sectionOrder(table)))
                .toList();
        int candidate = -1;
        for (int cut = 0; cut <= tables.size(); cut++) {
            if (!orderedAround(tables, cut, target.canonicalOrder())) {
                continue;
            }
            if (candidate >= 0) {
                return original.source().length();
            }
            candidate = cut;
        }
        if (candidate < 0 || candidate == tables.size()) {
            return original.source().length();
        }
        return lineStart(tables.get(candidate).table().headerSpan().start());
    }

    private static boolean orderedAround(List<TableOrder> tables, int cut, int targetOrder) {
        for (int index = 0; index < tables.size(); index++) {
            int order = tables.get(index).order();
            if ((index < cut && order > targetOrder)
                    || (index >= cut && order < targetOrder)) {
                return false;
            }
        }
        return true;
    }

    private static int sectionOrder(TableSyntax table) {
        return SCHEMA.matchSection(new ManifestPath(table.path()))
                .map(match -> match.descriptor().canonicalOrder())
                .orElseThrow(() -> unsafe("table schema metadata is unavailable"));
    }

    private int lineStart(int offset) {
        int current = offset;
        while (current > 0 && original.source().charAt(current - 1) != '\n') {
            current--;
        }
        return current;
    }

    private void appendSeparatedTable(
            StringBuilder insertion,
            int offset,
            List<String> tablePath,
            String assignments) {
        String prefix = original.source().substring(0, offset) + insertion;
        if (!prefix.isEmpty() && !prefix.endsWith(newline)) {
            insertion.append(newline);
            prefix += newline;
        }
        if (!prefix.isEmpty() && !prefix.endsWith(newline + newline)) {
            insertion.append(newline);
        }
        insertion.append('[')
                .append(String.join(".", tablePath))
                .append(']')
                .append(newline)
                .append(assignments);
        if (offset < original.source().length()) {
            insertion.append(newline);
        }
    }

    private String assignments(List<Addition> additions) {
        StringBuilder result = new StringBuilder();
        additions.forEach(addition -> result.append(addition.assignment())
                .append(addition.commentSuffix())
                .append(newline));
        return result.toString();
    }

    private boolean needsLineBreakBefore(int offset) {
        return offset > 0 && original.source().charAt(offset - 1) != '\n';
    }

    private static boolean hasMixedNewlines(String source) {
        boolean hasCrlf = source.contains("\r\n");
        if (!hasCrlf) {
            return false;
        }
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '\n'
                    && (index == 0 || source.charAt(index - 1) != '\r')) {
                return true;
            }
        }
        return false;
    }

    private AssignmentSyntax sourceEntry(List<String> path) {
        List<AssignmentSyntax> assignments = original.syntax().sourceIndex().assignmentsAt(path);
        if (assignments.size() != 1) {
            throw unsafe("source position for `" + String.join(".", path) + "` is unavailable");
        }
        AssignmentSyntax assignment = assignments.getFirst();
        List<String> parent = path.subList(0, path.size() - 1);
        if (!assignment.tablePath().equals(parent) || assignment.keyPath().size() != 1) {
            throw unsafe("source span for `" + String.join(".", path) + "` is not editable");
        }
        return assignment;
    }

    private String applyEdits() {
        edits.sort(Comparator.comparingInt(TextEdit::start)
                .thenComparingInt(TextEdit::end)
                .reversed());
        int previousStart = original.source().length() + 1;
        StringBuilder result = new StringBuilder(original.source());
        for (TextEdit edit : edits) {
            if (edit.end() > previousStart) {
                throw unsafe("the requested source patches overlap");
            }
            result.replace(edit.start(), edit.end(), edit.replacement());
            previousStart = edit.start();
        }
        return result.toString();
    }

    private static Map<List<String>, CanonicalEntry> mutableEntries(
            ZoltManifestDocument document) {
        LinkedHashMap<List<String>, CanonicalEntry> entries = new LinkedHashMap<>();
        for (AssignmentSyntax assignment : document.syntax().assignments()) {
            List<String> path = assignment.fullPath();
            ManifestField field = SCHEMA.matchField(new ManifestPath(path))
                    .map(match -> match.descriptor())
                    .filter(candidate -> candidate.mutation() == MutationPolicy.REPLACE_ENTRY)
                    .orElse(null);
            if (field == null) {
                continue;
            }
            CanonicalEntry entry = new CanonicalEntry(
                    path,
                    List.copyOf(path.subList(0, path.size() - 1)),
                    field,
                    assignment.assignmentSpan().text(document.source()),
                    assignment.valueSpan().text(document.source()));
            if (entries.putIfAbsent(entry.path(), entry) != null) {
                throw unsafe("canonical mutable entry paths are not unique");
            }
        }
        return Collections.unmodifiableMap(entries);
    }

    private static IllegalStateException unsafe(String reason) {
        return new IllegalStateException(
                "Could not safely edit zolt.toml because " + reason + ". No changes were written.");
    }

    private record CanonicalEntry(
            List<String> path,
            List<String> parent,
            ManifestField field,
            String assignment,
            String value) {
    }

    private record Addition(String assignment, String commentSuffix) {
    }

    private record TableOrder(TableSyntax table, int order) {
    }

    private record TextEdit(int start, int end, String replacement) {
    }
}
