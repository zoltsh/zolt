package sh.zolt.update;

import sh.zolt.dependency.UpdateClass;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Renders an outdated report as deterministic JSON schema v1. Keys are stable and absent values are
 * emitted as {@code null} rather than omitted, so downstream consumers (Renovate custom datasource,
 * CI gates) can rely on a fixed shape.
 */
public final class OutdatedJsonRenderer {
    public String render(OutdatedReport report) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        field(json, 1, "schemaVersion", "1", false, false);
        stringField(json, 1, "command", "outdated", false);
        stringField(json, 1, "status", "ok", false);
        diagnostics(json, report.notes());
        key(json, 1, "scopes");
        json.append("[");
        renderScopes(json, report.scopes());
        json.append("],\n");
        arrayOfStrings(json, 1, "notes", report.notes(), true);
        json.append("}\n");
        return json.toString();
    }

    private static void diagnostics(StringBuilder json, List<String> notes) {
        indent(json, 1);
        json.append("\"diagnostics\": ");
        if (notes.isEmpty()) {
            json.append("[],\n");
            return;
        }
        json.append("[\n");
        for (int index = 0; index < notes.size(); index++) {
            indent(json, 2);
            json.append("{\"severity\": \"warning\", \"message\": ")
                    .append(quote(notes.get(index)))
                    .append("}")
                    .append(index + 1 < notes.size() ? ",\n" : "\n");
        }
        indent(json, 1);
        json.append("],\n");
    }

    private void renderScopes(StringBuilder json, List<OutdatedScopeReport> scopes) {
        if (scopes.isEmpty()) {
            return;
        }
        json.append("\n");
        for (int index = 0; index < scopes.size(); index++) {
            OutdatedScopeReport scope = scopes.get(index);
            indent(json, 2);
            json.append("{\n");
            stringField(json, 3, "label", scope.label(), false);
            key(json, 3, "entries");
            json.append("[");
            renderEntries(json, scope.entries());
            json.append("]\n");
            indent(json, 2);
            json.append(index + 1 < scopes.size() ? "},\n" : "}\n");
        }
        indent(json, 1);
    }

    private void renderEntries(StringBuilder json, List<OutdatedEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        json.append("\n");
        for (int index = 0; index < entries.size(); index++) {
            renderEntry(json, entries.get(index), index + 1 < entries.size());
        }
        indent(json, 3);
    }

    private void renderEntry(StringBuilder json, OutdatedEntry entry, boolean more) {
        indent(json, 4);
        json.append("{\n");
        stringField(json, 5, "surface", entry.surface().jsonName(), false);
        stringField(json, 5, "identifier", entry.identifier(), false);
        stringField(json, 5, "section", entry.section(), false);
        stringField(json, 5, "current", entry.currentVersion(), false);
        stringField(json, 5, "status", entry.status().jsonName(), false);
        renderCandidates(json, entry.candidates());
        optionalStringField(json, 5, "selectedInMajor", entry.candidates().selectedInMajor(), false);
        optionalStringField(json, 5, "selectedInMajorClass", classText(entry.candidates().selectedInMajorClass()), false);
        optionalStringField(json, 5, "selectedLatest", entry.candidates().selectedLatest(), false);
        optionalStringField(json, 5, "selectedLatestClass", classText(entry.candidates().selectedLatestClass()), false);
        optionalStringField(json, 5, "source", entry.sourceRepository(), false);
        arrayOfStrings(json, 5, "governs", entry.governs(), false);
        arrayOfStrings(json, 5, "members", entry.members(), false);
        arrayOfStrings(json, 5, "notes", entry.notes(), true);
        indent(json, 4);
        json.append(more ? "},\n" : "}\n");
    }

    private void renderCandidates(StringBuilder json, OutdatedCandidates candidates) {
        key(json, 5, "candidates");
        json.append("{\n");
        optionalStringField(json, 6, "patch", candidates.patch(), false);
        optionalStringField(json, 6, "minor", candidates.minor(), false);
        optionalStringField(json, 6, "major", candidates.major(), true);
        indent(json, 5);
        json.append("},\n");
    }

    private static Optional<String> classText(Optional<UpdateClass> updateClass) {
        return updateClass.map(value -> value.name().toLowerCase(Locale.ROOT));
    }

    private static void stringField(StringBuilder json, int level, String name, String value, boolean last) {
        field(json, level, name, quote(value), false, last);
    }

    private static void optionalStringField(
            StringBuilder json, int level, String name, Optional<String> value, boolean last) {
        field(json, level, name, value.map(OutdatedJsonRenderer::quote).orElse("null"), false, last);
    }

    private static void field(
            StringBuilder json, int level, String name, String rendered, boolean unusedRaw, boolean last) {
        indent(json, level);
        json.append('"').append(name).append("\": ").append(rendered);
        json.append(last ? "\n" : ",\n");
    }

    private static void arrayOfStrings(
            StringBuilder json, int level, String name, List<String> values, boolean last) {
        indent(json, level);
        json.append('"').append(name).append("\": ");
        if (values.isEmpty()) {
            json.append("[]");
        } else {
            json.append("[\n");
            for (int index = 0; index < values.size(); index++) {
                indent(json, level + 1);
                json.append(quote(values.get(index)));
                json.append(index + 1 < values.size() ? ",\n" : "\n");
            }
            indent(json, level);
            json.append("]");
        }
        json.append(last ? "\n" : ",\n");
    }

    private static void key(StringBuilder json, int level, String name) {
        indent(json, level);
        json.append('"').append(name).append("\": ");
    }

    private static void indent(StringBuilder json, int level) {
        Json.indent(json, level);
    }

    private static String quote(String value) {
        return Json.quote(value);
    }
}
