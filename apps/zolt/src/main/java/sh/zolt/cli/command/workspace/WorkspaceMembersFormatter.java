package sh.zolt.cli.command.workspace;

import java.util.List;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.workspace.discovery.DiscoveredWorkspace;
import sh.zolt.workspace.discovery.DiscoveredWorkspaceMember;

/**
 * Deterministic human and schema-v1 projections of final workspace discovery.
 *
 * <p>Design §6.2: an authored exclusion that matched no expanded candidate is allowed but reported.
 * Schema v1 always carries {@code staleExclusions} so automation reads one closed shape; the text
 * projection prints the line only when there is something to report.
 */
final class WorkspaceMembersFormatter {
    String text(DiscoveredWorkspace workspace) {
        StringBuilder output = new StringBuilder();
        output.append("Workspace ").append(workspaceName(workspace)).append('\n')
                .append("  manifest: zolt.toml\n")
                .append("  selection: ").append(workspace.selection().source().value()).append('\n')
                .append("  selected: ").append(joinPaths(workspace.selection().members())).append('\n');
        if (!workspace.staleExclusions().isEmpty()) {
            output.append("  stale excludes: ").append(joinPatterns(workspace)).append('\n');
        }
        output.append('\n').append("Members\n");
        for (DiscoveredWorkspaceMember member : workspace.members().values()) {
            output.append("  ").append(member.path()).append('\n')
                    .append("    manifest: ").append(member.manifestPath()).append('\n')
                    .append("    project: ").append(projectName(workspace, member)).append('\n')
                    .append("    matched by: ").append(member.matchedBy().stream()
                            .map(Object::toString)
                            .reduce((left, right) -> left + ", " + right)
                            .orElseThrow())
                    .append('\n');
        }
        return output.toString();
    }

    String json(DiscoveredWorkspace workspace) {
        StringBuilder output = new StringBuilder();
        output.append("{\n")
                .append("  \"schemaVersion\": 1,\n")
                .append("  \"workspace\": {\n")
                .append("    \"name\": ").append(quote(workspaceName(workspace))).append(",\n")
                .append("    \"manifestPath\": \"zolt.toml\",\n")
                .append("    \"selection\": {\n")
                .append("      \"source\": ").append(quote(workspace.selection().source().value())).append(",\n")
                .append("      \"members\": ");
        stringArray(output, workspace.selection().members().stream()
                .map(WorkspaceMemberPath::value)
                .toList());
        output.append("\n    },\n")
                .append("    \"members\": [\n");
        int index = 0;
        for (DiscoveredWorkspaceMember member : workspace.members().values()) {
            output.append("      {\n")
                    .append("        \"path\": ").append(quote(member.path().value())).append(",\n")
                    .append("        \"manifestPath\": ").append(quote(member.manifestPath())).append(",\n")
                    .append("        \"projectName\": ").append(quote(projectName(workspace, member))).append(",\n")
                    .append("        \"matchedBy\": ");
            stringArray(output, member.matchedBy().stream().map(Object::toString).toList());
            output.append("\n      }");
            if (++index < workspace.members().size()) {
                output.append(',');
            }
            output.append('\n');
        }
        output.append("    ],\n")
                .append("    \"staleExclusions\": ");
        stringArray(output, workspace.staleExclusions().stream().map(Object::toString).toList());
        return output.append("\n  }\n}\n").toString();
    }

    private static String joinPatterns(DiscoveredWorkspace workspace) {
        return workspace.staleExclusions().stream()
                .map(Object::toString)
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
    }

    private static String workspaceName(DiscoveredWorkspace workspace) {
        return workspace.effective().workspace().name().value();
    }

    private static String projectName(
            DiscoveredWorkspace workspace,
            DiscoveredWorkspaceMember member) {
        return workspace.effective().members().get(member.path())
                .project().identity().name().value().value();
    }

    private static String joinPaths(List<WorkspaceMemberPath> paths) {
        return paths.stream()
                .map(WorkspaceMemberPath::value)
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
    }

    private static void stringArray(StringBuilder output, List<String> values) {
        output.append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                output.append(", ");
            }
            output.append(quote(values.get(index)));
        }
        output.append(']');
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }
}
