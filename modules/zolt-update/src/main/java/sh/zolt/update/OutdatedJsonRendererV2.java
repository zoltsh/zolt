package sh.zolt.update;

import sh.zolt.dependency.UpdateClass;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Deterministic automation contract for outdated JSON schema v2. */
public final class OutdatedJsonRendererV2 {
    public String render(OutdatedReport report) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        field(json, 1, "schemaVersion", "2", false);
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
                    .append(Json.quote(notes.get(index)))
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
            stringField(json, 3, "manifestPath", scope.manifestPath(), false);
            stringField(json, 3, "lockfilePath", scope.lockfilePath(), false);
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
        UpdateTarget target = entry.target();
        indent(json, 4);
        json.append("{\n");
        stringField(json, 5, "targetId", target.targetId().value(), false);
        booleanField(json, 5, "updateable", target.updateable(), false);
        optionalStringField(json, 5, "updateBlocker", target.updateBlocker(), false);
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

    private static void renderCandidates(StringBuilder json, OutdatedCandidates candidates) {
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
        field(json, level, name, Json.quote(value), last);
    }

    private static void booleanField(StringBuilder json, int level, String name, boolean value, boolean last) {
        field(json, level, name, Boolean.toString(value), last);
    }

    private static void optionalStringField(
            StringBuilder json, int level, String name, Optional<String> value, boolean last) {
        field(json, level, name, value.map(Json::quote).orElse("null"), last);
    }

    private static void field(StringBuilder json, int level, String name, String rendered, boolean last) {
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
                json.append(Json.quote(values.get(index)));
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
}
