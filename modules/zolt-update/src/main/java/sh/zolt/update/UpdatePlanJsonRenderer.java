package sh.zolt.update;

import java.util.List;
import java.util.Locale;

/** Renders an update plan as deterministic JSON: {@code edits[]} and {@code skipped[]} plus warnings. */
public final class UpdatePlanJsonRenderer {
    public String render(UpdatePlan plan, boolean dryRun) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        Json.indent(json, 1);
        json.append("\"schemaVersion\": 1,\n");
        Json.indent(json, 1);
        json.append("\"command\": \"update\",\n");
        Json.indent(json, 1);
        json.append("\"status\": \"ok\",\n");
        Json.indent(json, 1);
        json.append("\"dryRun\": ").append(dryRun).append(",\n");
        renderEdits(json, plan.edits());
        renderSkips(json, plan.skips());
        diagnostics(json, plan.warnings());
        arrayField(json, 1, "warnings", plan.warnings(), true);
        json.append("}\n");
        return json.toString();
    }

    private static void diagnostics(StringBuilder json, List<String> warnings) {
        Json.indent(json, 1);
        json.append("\"diagnostics\": ");
        if (warnings.isEmpty()) {
            json.append("[],\n");
            return;
        }
        json.append("[\n");
        for (int index = 0; index < warnings.size(); index++) {
            Json.indent(json, 2);
            json.append("{\"severity\": \"warning\", \"message\": ")
                    .append(Json.quote(warnings.get(index)))
                    .append("}")
                    .append(index + 1 < warnings.size() ? ",\n" : "\n");
        }
        Json.indent(json, 1);
        json.append("],\n");
    }

    private void renderEdits(StringBuilder json, List<UpdateEdit> edits) {
        Json.indent(json, 1);
        json.append("\"edits\": ");
        if (edits.isEmpty()) {
            json.append("[],\n");
            return;
        }
        json.append("[\n");
        for (int index = 0; index < edits.size(); index++) {
            UpdateEdit edit = edits.get(index);
            Json.indent(json, 2);
            json.append("{\n");
            stringField(json, 3, "surface", edit.surface().jsonName(), false);
            stringField(json, 3, "identifier", edit.identifier(), false);
            stringField(json, 3, "section", edit.section(), false);
            stringField(json, 3, "from", edit.fromVersion(), false);
            stringField(json, 3, "to", edit.toVersion(), false);
            stringField(json, 3, "class", edit.changeClass().name().toLowerCase(Locale.ROOT), false);
            arrayField(json, 3, "fanOut", edit.fanOut(), true);
            Json.indent(json, 2);
            json.append(index + 1 < edits.size() ? "},\n" : "}\n");
        }
        Json.indent(json, 1);
        json.append("],\n");
    }

    private void renderSkips(StringBuilder json, List<UpdateSkip> skips) {
        Json.indent(json, 1);
        json.append("\"skipped\": ");
        if (skips.isEmpty()) {
            json.append("[],\n");
            return;
        }
        json.append("[\n");
        for (int index = 0; index < skips.size(); index++) {
            UpdateSkip skip = skips.get(index);
            Json.indent(json, 2);
            json.append("{\n");
            stringField(json, 3, "surface", skip.surface().jsonName(), false);
            stringField(json, 3, "identifier", skip.identifier(), false);
            stringField(json, 3, "section", skip.section(), false);
            stringField(json, 3, "reason", skip.reason(), true);
            Json.indent(json, 2);
            json.append(index + 1 < skips.size() ? "},\n" : "}\n");
        }
        Json.indent(json, 1);
        json.append("],\n");
    }

    private static void stringField(StringBuilder json, int level, String name, String value, boolean last) {
        Json.indent(json, level);
        json.append('"').append(name).append("\": ").append(Json.quote(value)).append(last ? "\n" : ",\n");
    }

    private static void arrayField(StringBuilder json, int level, String name, List<String> values, boolean last) {
        Json.indent(json, level);
        json.append('"').append(name).append("\": ");
        if (values.isEmpty()) {
            json.append("[]");
        } else {
            json.append("[\n");
            for (int index = 0; index < values.size(); index++) {
                Json.indent(json, level + 1);
                json.append(Json.quote(values.get(index))).append(index + 1 < values.size() ? ",\n" : "\n");
            }
            Json.indent(json, level);
            json.append("]");
        }
        json.append(last ? "\n" : ",\n");
    }
}
