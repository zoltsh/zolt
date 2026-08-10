package sh.zolt.quality;

import java.nio.file.Path;

public final class QualityCheckFormatter {
    private QualityCheckFormatter() {
    }

    public static String text(QualityCheckReport report) {
        StringBuilder output = new StringBuilder();
        for (QualityCheckResult check : report.checks()) {
            output.append(check.status().marker())
                    .append(' ')
                    .append(check.id())
                    .append(' ');
            check.member().ifPresent(member -> output.append(member).append(' '));
            output.append(check.subject())
                    .append(' ')
                    .append(check.message())
                    .append(System.lineSeparator());
            if (!check.nextStep().isBlank()) {
                output.append("  next: ")
                        .append(check.nextStep())
                        .append(System.lineSeparator());
            }
        }
        return output.toString();
    }

    public static String json(QualityCheckReport report) {
        StringBuilder output = new StringBuilder();
        output.append('{');
        output.append("\"schemaVersion\":1,");
        jsonField(output, "command", "check");
        output.append(',');
        jsonField(output, "status", report.ok() ? "ok" : "failed");
        output.append(',');
        jsonField(output, "projectRoot", normalize(report.projectRoot()));
        output.append(",\"workspace\":").append(report.workspace());
        output.append(",\"checks\":[");
        boolean first = true;
        for (QualityCheckResult check : report.checks()) {
            if (!first) {
                output.append(',');
            }
            appendCheck(output, check);
            first = false;
        }
        output.append("],\"diagnostics\":[");
        appendFailedChecks(output, report);
        output.append("],\"blockers\":[");
        appendFailedChecks(output, report);
        output.append("]}").append(System.lineSeparator());
        return output.toString();
    }

    private static void appendFailedChecks(StringBuilder output, QualityCheckReport report) {
        boolean first = true;
        for (QualityCheckResult check : report.checks()) {
            if (check.status() != QualityCheckStatus.FAILED) {
                continue;
            }
            if (!first) {
                output.append(',');
            }
            appendBlocker(output, check);
            first = false;
        }
    }

    private static void appendCheck(StringBuilder output, QualityCheckResult check) {
        output.append('{');
        jsonField(output, "id", check.id());
        output.append(',');
        jsonField(output, "severity", check.severity().jsonValue());
        output.append(',');
        jsonField(output, "status", check.status().jsonValue());
        output.append(',');
        output.append("\"member\":");
        if (check.member().isPresent()) {
            jsonString(output, check.member().orElseThrow());
        } else {
            output.append("null");
        }
        output.append(',');
        jsonField(output, "subject", check.subject());
        output.append(',');
        jsonField(output, "message", check.message());
        output.append(',');
        jsonField(output, "nextStep", check.nextStep());
        output.append('}');
    }

    private static void appendBlocker(StringBuilder output, QualityCheckResult check) {
        output.append('{');
        jsonField(output, "id", check.id());
        output.append(',');
        jsonField(output, "severity", check.severity().jsonValue());
        output.append(',');
        output.append("\"member\":");
        if (check.member().isPresent()) {
            jsonString(output, check.member().orElseThrow());
        } else {
            output.append("null");
        }
        output.append(',');
        jsonField(output, "subject", check.subject());
        output.append(',');
        jsonField(output, "message", check.message());
        output.append(',');
        jsonField(output, "nextStep", check.nextStep());
        output.append('}');
    }

    private static String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static void jsonField(StringBuilder output, String name, String value) {
        jsonString(output, name);
        output.append(':');
        jsonString(output, value);
    }

    private static void jsonString(StringBuilder output, String value) {
        output.append('"').append(escapeJson(value)).append('"');
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
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
        return escaped.toString();
    }
}
