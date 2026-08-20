package sh.zolt.toml;

import java.util.List;
import sh.zolt.toml.schema.ManifestValueKind;

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
