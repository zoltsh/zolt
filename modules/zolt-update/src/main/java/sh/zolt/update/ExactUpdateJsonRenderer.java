package sh.zolt.update;

import java.util.List;
import java.util.Locale;

/** Renders the stable schema-v2 exact update success envelope. */
public final class ExactUpdateJsonRenderer {
    public String render(ExactUpdateResult result) {
        ExactUpdatePlan plan = result.plan();
        UpdateTarget target = plan.target();
        StringBuilder json = new StringBuilder();
        json.append("{\n")
                .append("  \"schemaVersion\": 2,\n")
                .append("  \"command\": \"update\",\n")
                .append("  \"status\": \"ok\",\n")
                .append("  \"dryRun\": ").append(result.dryRun()).append(",\n")
                .append("  \"target\": {\n");
        field(json, 2, "targetId", target.targetId().toString(), false);
        field(json, 2, "manifestPath", target.manifestPath(), false);
        field(json, 2, "lockfilePath", target.lockfilePath(), false);
        field(json, 2, "surface", target.surface().jsonName(), false);
        field(json, 2, "identifier", target.identifier(), false);
        field(json, 2, "section", target.section(), false);
        json.append("    \"updateable\": ").append(target.updateable()).append("\n")
                .append("  },\n");
        field(json, 1, "from", plan.fromVersion(), false);
        field(json, 1, "to", plan.toVersion(), false);
        json.append("  \"class\": ")
                .append(plan.changeClass()
                        .map(value -> Json.quote(value.name().toLowerCase(Locale.ROOT)))
                        .orElse("null"))
                .append(",\n")
                .append("  \"changed\": ").append(result.changed()).append(",\n")
                .append("  \"applied\": ").append(result.applied()).append(",\n")
                .append("  \"resolved\": ").append(result.resolved()).append(",\n");
        array(json, "changedFiles", result.changedFiles());
        array(json, "fanOut", target.governs());
        diagnostics(json, plan.warnings());
        return json.append("}\n").toString();
    }

    private static void diagnostics(StringBuilder json, List<String> warnings) {
        json.append("  \"diagnostics\": ");
        if (warnings.isEmpty()) {
            json.append("[]\n");
            return;
        }
        json.append("[\n");
        for (int index = 0; index < warnings.size(); index++) {
            json.append("    {\"severity\": \"warning\", \"message\": ")
                    .append(Json.quote(warnings.get(index)))
                    .append("}")
                    .append(index + 1 < warnings.size() ? ",\n" : "\n");
        }
        json.append("  ]\n");
    }

    private static void array(StringBuilder json, String name, List<String> values) {
        json.append("  \"").append(name).append("\": ");
        if (values.isEmpty()) {
            json.append("[],\n");
            return;
        }
        json.append("[\n");
        for (int index = 0; index < values.size(); index++) {
            json.append("    ").append(Json.quote(values.get(index)))
                    .append(index + 1 < values.size() ? ",\n" : "\n");
        }
        json.append("  ],\n");
    }

    private static void field(StringBuilder json, int level, String name, String value, boolean last) {
        Json.indent(json, level);
        json.append('"').append(name).append("\": ").append(Json.quote(value)).append(last ? "\n" : ",\n");
    }
}
