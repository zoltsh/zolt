package sh.zolt.plan;

import java.nio.file.Path;
import java.util.List;

public final class BuildPlanFormatter {
    public String text(BuildPlan plan) {
        StringBuilder output = new StringBuilder();
        output.append("Zolt plan\n");
        output.append("Project: ").append(plan.projectName()).append('\n');
        output.append("Root: ").append(plan.projectRoot()).append('\n');
        output.append("Lockfile: ")
                .append(plan.lockfilePath())
                .append(plan.workspaceLockfile() ? " (workspace)" : "")
                .append('\n');
        output.append("Target: ").append(plan.target().configValue()).append('\n');
        output.append("Status: ").append(plan.blocked() ? "blocked" : "ready").append('\n');
        output.append("Nodes: ").append(plan.nodes().size()).append('\n');
        for (PlanNode node : plan.nodes()) {
            output.append("- ")
                    .append(node.id())
                    .append(" [")
                    .append(node.kind())
                    .append("] ")
                    .append(node.status().configValue())
                    .append(" - ")
                    .append(node.description())
                    .append('\n');
            if (!node.inputs().isEmpty()) {
                output.append("  inputs: ").append(String.join(", ", node.inputs())).append('\n');
            }
            if (!node.outputs().isEmpty()) {
                output.append("  outputs: ").append(String.join(", ", node.outputs())).append('\n');
            }
            if (!node.details().isEmpty()) {
                output.append("  details: ").append(String.join("; ", node.details())).append('\n');
            }
            for (PlanBlocker blocker : node.blockers()) {
                output.append("  blocker ")
                        .append(blocker.code())
                        .append(": ")
                        .append(blocker.message())
                        .append('\n')
                        .append("    next: ")
                        .append(blocker.nextStep())
                        .append('\n');
            }
        }
        return output.toString();
    }

    public String json(BuildPlan plan) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        field(json, 1, "schemaVersion", plan.schemaVersion(), true);
        stringField(json, 1, "projectRoot", path(plan.projectRoot()), true);
        stringField(json, 1, "lockfilePath", path(plan.lockfilePath()), true);
        field(json, 1, "workspaceLockfile", plan.workspaceLockfile(), true);
        stringField(json, 1, "project", plan.projectName(), true);
        stringField(json, 1, "target", plan.target().configValue(), true);
        stringField(json, 1, "status", plan.blocked() ? "blocked" : "ready", true);
        nodes(json, plan.nodes());
        json.append("\n}\n");
        return json.toString();
    }

    private static void nodes(StringBuilder json, List<PlanNode> nodes) {
        indent(json, 1).append("\"nodes\": [");
        if (!nodes.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < nodes.size(); index++) {
                PlanNode node = nodes.get(index);
                indent(json, 2).append("{\n");
                stringField(json, 3, "id", node.id(), true);
                stringField(json, 3, "kind", node.kind(), true);
                stringField(json, 3, "status", node.status().configValue(), true);
                stringField(json, 3, "description", node.description(), true);
                stringArrayField(json, 3, "inputs", node.inputs(), true);
                stringArrayField(json, 3, "outputs", node.outputs(), true);
                stringArrayField(json, 3, "details", node.details(), true);
                blockers(json, node.blockers());
                json.append('\n');
                indent(json, 2).append("}");
                if (index + 1 < nodes.size()) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, 1);
        }
        json.append("]");
    }

    private static void blockers(StringBuilder json, List<PlanBlocker> blockers) {
        indent(json, 3).append("\"blockers\": [");
        if (!blockers.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < blockers.size(); index++) {
                PlanBlocker blocker = blockers.get(index);
                indent(json, 4).append("{\n");
                stringField(json, 5, "code", blocker.code(), true);
                stringField(json, 5, "message", blocker.message(), true);
                stringField(json, 5, "nextStep", blocker.nextStep(), false);
                indent(json, 4).append("}");
                if (index + 1 < blockers.size()) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, 3);
        }
        json.append("]");
    }

    private static void field(StringBuilder json, int level, String name, boolean value, boolean trailingComma) {
        indent(json, level);
        string(json, name);
        json.append(": ").append(value);
        if (trailingComma) {
            json.append(',');
        }
        json.append('\n');
    }

    private static void field(StringBuilder json, int level, String name, int value, boolean trailingComma) {
        indent(json, level);
        string(json, name);
        json.append(": ").append(value);
        if (trailingComma) {
            json.append(',');
        }
        json.append('\n');
    }

    private static void stringField(StringBuilder json, int level, String name, String value, boolean trailingComma) {
        indent(json, level);
        string(json, name);
        json.append(": ");
        string(json, value);
        if (trailingComma) {
            json.append(',');
        }
        json.append('\n');
    }

    private static void stringArrayField(
            StringBuilder json,
            int level,
            String name,
            List<String> values,
            boolean trailingComma) {
        indent(json, level);
        string(json, name);
        json.append(": [");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(", ");
            }
            string(json, values.get(index));
        }
        json.append("]");
        if (trailingComma) {
            json.append(',');
        }
        json.append('\n');
    }

    private static String path(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static void string(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }

    private static StringBuilder indent(StringBuilder json, int level) {
        return json.append("  ".repeat(level));
    }
}
