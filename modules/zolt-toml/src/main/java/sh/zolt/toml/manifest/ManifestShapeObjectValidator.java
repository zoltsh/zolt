package sh.zolt.toml.manifest;

import java.util.Comparator;
import java.util.List;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;
import sh.zolt.toml.schema.ManifestObjectMember;
import sh.zolt.toml.schema.ManifestObjectShape;
import sh.zolt.toml.schema.ManifestValueKind;
import sh.zolt.toml.syntax.SourceSpan;
import sh.zolt.toml.syntax.TableSyntax;

/** Closed member, value-kind, and presence checks for one inline object. */
final class ManifestShapeObjectValidator {
    private final ManifestShapeDiagnostics diagnostics;

    ManifestShapeObjectValidator(ManifestShapeDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    boolean validate(
            ManifestObjectShape shape,
            TomlTable table,
            ManifestShapeSource source,
            String path) {
        boolean valid = validateMembers(shape, table, source, path);
        valid &= validateRequired(shape, table, source, path);
        valid &= validatePresence(shape, table, source, path);
        return valid;
    }

    private boolean validateMembers(
            ManifestObjectShape shape,
            TomlTable table,
            ManifestShapeSource source,
            String path) {
        boolean valid = true;
        for (var entry : table.entrySet()) {
            String memberPath = path + "." + entry.getKey();
            var member = shape.member(entry.getKey());
            if (member.isEmpty()) {
                ManifestObjectMember suggestion = nearest(entry.getKey(), shape.members());
                diagnostics.add(source, "Unknown manifest field `" + memberPath
                        + "`. Did you mean `" + path + "." + suggestion.name() + "`?");
                valid = false;
            } else if (!ManifestShapeValueKinds.matches(
                    member.orElseThrow().valueKind(), entry.getValue())) {
                ManifestObjectMember descriptor = member.orElseThrow();
                diagnostics.add(source, "Invalid value for `" + memberPath + "`: expected "
                        + ManifestShapeValueKinds.expected(descriptor.valueKind()) + " but found "
                        + ManifestShapeValueKinds.actual(entry.getValue()) + ".");
                valid = false;
            }
        }
        return valid;
    }

    private boolean validateRequired(
            ManifestObjectShape shape,
            TomlTable table,
            ManifestShapeSource source,
            String path) {
        boolean valid = true;
        for (ManifestObjectMember member : shape.members()) {
            if (member.required() && !table.keySet().contains(member.name())) {
                diagnostics.add(source, "Missing required inline-object field `"
                        + path + "." + member.name() + "`.");
                valid = false;
            }
        }
        return valid;
    }

    private boolean validatePresence(
            ManifestObjectShape shape,
            TomlTable table,
            ManifestShapeSource source,
            String path) {
        boolean valid = true;
        for (ManifestObjectShape.PresenceGroup group : shape.presenceGroups()) {
            long present = group.members().stream()
                    .filter(member -> table.keySet().contains(member.name()))
                    .count();
            boolean accepted = switch (group.rule()) {
                case AT_LEAST_ONE -> present >= 1;
                case EXACTLY_ONE -> present == 1;
            };
            if (!accepted) {
                String requirement = group.rule() == ManifestObjectShape.PresenceRule.AT_LEAST_ONE
                        ? "at least one"
                        : "exactly one";
                diagnostics.add(source, "Inline object `" + path + "` must declare "
                        + requirement + " of " + memberNames(group.members()) + ".");
                valid = false;
            }
        }
        return valid;
    }

    private static ManifestObjectMember nearest(
            String observed,
            List<ManifestObjectMember> members) {
        return members.stream()
                .min(Comparator.comparingInt((ManifestObjectMember member) ->
                                distance(observed, member.name()))
                        .thenComparingInt(ManifestObjectMember::canonicalOrder)
                        .thenComparing(ManifestObjectMember::name))
                .orElseThrow();
    }

    private static String memberNames(List<ManifestObjectMember> members) {
        return members.stream()
                .map(member -> "`" + member.name() + "`")
                .collect(java.util.stream.Collectors.joining(" or "));
    }

    private static int distance(String left, String right) {
        int[] a = left.codePoints().toArray();
        int[] b = right.codePoints().toArray();
        int[] previous = new int[b.length + 1];
        for (int index = 0; index <= b.length; index++) {
            previous[index] = index;
        }
        for (int row = 1; row <= a.length; row++) {
            int[] current = new int[b.length + 1];
            current[0] = row;
            for (int column = 1; column <= b.length; column++) {
                int substitution = previous[column - 1]
                        + (a[row - 1] == b[column - 1] ? 0 : 1);
                current[column] = Math.min(
                        Math.min(previous[column] + 1, current[column - 1] + 1),
                        substitution);
            }
            previous = current;
        }
        return previous[b.length];
    }
}

/** Source-layout predicates and diagnostics shared by manifest-shape checks. */
final class ManifestShapeText {
    private ManifestShapeText() {
    }

    static boolean acceptsInlineTable(ManifestValueKind kind) {
        return switch (kind) {
            case INLINE_TABLE, STRING_OR_INLINE_TABLE, BOOLEAN_OR_STRING_OR_INLINE_TABLE -> true;
            default -> false;
        };
    }

    static boolean canonicalHeader(List<String> path, TableSyntax table, String source) {
        return table.explicit() && table.headerSpan().text(source).equals(sectionPath(path));
    }

    static boolean onePhysicalLine(ManifestShapeSource source, String text) {
        return source.assignment()
                .map(assignment -> spanIsOneLine(assignment.assignmentSpan(), text))
                .orElse(false);
    }

    static boolean spanIsOneLine(SourceSpan span, String source) {
        String text = span.text(source);
        return text.indexOf('\n') < 0 && text.indexOf('\r') < 0;
    }

    static String mutableMessage(List<String> parent, List<String> field) {
        return "Entries in " + sectionPath(parent)
                + " must use one physical assignment line under the explicit canonical table header. "
                + "Rewrite `" + dotted(field) + "` beneath `" + sectionPath(parent) + "`.";
    }

    static String dotted(List<String> path) {
        return String.join(".", path);
    }

    static String sectionPath(List<String> path) {
        return "[" + dotted(path) + "]";
    }
}

/** Shared raw Tomlj value-kind checks and diagnostic names. */
final class ManifestShapeValueKinds {
    private ManifestShapeValueKinds() {
    }

    static boolean matches(ManifestValueKind kind, Object value) {
        return switch (kind) {
            case STRING -> value instanceof String;
            case INTEGER -> value instanceof Long;
            case NUMBER -> value instanceof Long || value instanceof Double;
            case BOOLEAN -> value instanceof Boolean;
            case STRING_ARRAY -> stringArray(value);
            case INLINE_TABLE -> value instanceof TomlTable;
            case INLINE_TABLE_ARRAY -> tableArray(value);
            case STRING_OR_INLINE_TABLE -> value instanceof String || value instanceof TomlTable;
            case BOOLEAN_OR_STRING_ARRAY -> value instanceof Boolean || stringArray(value);
            case BOOLEAN_OR_STRING_OR_INLINE_TABLE ->
                value instanceof Boolean || value instanceof String || value instanceof TomlTable;
        };
    }

    static String expected(ManifestValueKind kind) {
        return kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    static String actual(Object value) {
        if (value instanceof String) return "string";
        if (value instanceof Long) return "integer";
        if (value instanceof Double) return "number";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof TomlArray) return "array";
        if (value instanceof TomlTable) return "table";
        return value.getClass().getSimpleName();
    }

    private static boolean stringArray(Object value) {
        if (!(value instanceof TomlArray array)) {
            return false;
        }
        for (int index = 0; index < array.size(); index++) {
            if (!(array.get(index) instanceof String)) {
                return false;
            }
        }
        return true;
    }

    private static boolean tableArray(Object value) {
        if (!(value instanceof TomlArray array)) {
            return false;
        }
        for (int index = 0; index < array.size(); index++) {
            if (!(array.get(index) instanceof TomlTable)) {
                return false;
            }
        }
        return true;
    }
}
