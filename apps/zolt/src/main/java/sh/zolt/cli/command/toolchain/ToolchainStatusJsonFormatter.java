package sh.zolt.cli.command.toolchain;

import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.toolchain.JavaToolchainStatus;
import sh.zolt.toolchain.TestRuntimeToolchain;
import sh.zolt.toolchain.jvm.ResolvedJavaToolchain;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class ToolchainStatusJsonFormatter {
    private ToolchainStatusJsonFormatter() {
    }

    static String json(String command, JavaToolchainStatus status) {
        return json(command, status, Optional.empty());
    }

    static String json(
            String command,
            JavaToolchainStatus status,
            Optional<TestRuntimeToolchain> testRuntime) {
        ResolvedJavaToolchain resolved = status.resolved();
        Optional<TestRuntimeToolchain> runtime = testRuntime == null
                ? Optional.empty()
                : testRuntime;
        boolean ok = status.ok() && runtime.stream().allMatch(TestRuntimeToolchain::ready);
        List<String> problems = new ArrayList<>(resolved.problems());
        runtime.filter(value -> !value.ready())
                .flatMap(TestRuntimeToolchain::problem)
                .ifPresent(problems::add);
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        intField(json, 1, "schemaVersion", 1, true);
        stringField(json, 1, "command", command, true);
        stringField(json, 1, "status", ok ? "ok" : "failed", true);
        diagnostics(json, problems);
        booleanField(json, 1, "ok", ok, true);
        request(json, status);
        json.append(",\n");
        resolved(json, resolved);
        runtime.ifPresent(value -> {
            json.append(",\n");
            testRuntime(json, value);
        });
        json.append("\n}\n");
        return json.toString();
    }

    private static void testRuntime(
            StringBuilder json,
            TestRuntimeToolchain runtime) {
        indent(json, 1).append("\"testRuntime\": {\n");
        stringField(json, 2, "version", runtime.request().version(), true);
        stringField(json, 2, "status", runtime.ready() ? "ok" : "error", true);
        booleanField(json, 2, "ok", runtime.ready(), true);
        optionalPathField(json, 2, "resolved", runtime.java(), true);
        optionalStringField(json, 2, "problem", runtime.problem(), true);
        optionalStringField(json, 2, "remediation", runtime.remediation(), false);
        indent(json, 1).append("}");
    }

    private static void diagnostics(StringBuilder json, List<String> problems) {
        fieldPrefix(json, 1, "diagnostics").append('[');
        if (!problems.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < problems.size(); index++) {
                indent(json, 2)
                        .append("{\"severity\": \"error\", \"message\": ")
                        .append(quote(problems.get(index)))
                        .append('}');
                json.append(index + 1 < problems.size() ? ",\n" : "\n");
            }
            indent(json, 1);
        }
        json.append("],\n");
    }

    private static void request(StringBuilder json, JavaToolchainStatus status) {
        indent(json, 1).append("\"request\": {\n");
        stringField(json, 2, "version", status.request().version(), true);
        stringField(json, 2, "source", status.requestSource(), true);
        stringField(json, 2, "distribution", status.request().distributionLabel(), true);
        stringArrayField(json, 2, "features", status.request().features().stream()
                .map(JavaFeature::id)
                .sorted()
                .toList(), true);
        stringField(json, 2, "policy", status.request().policy().id(), false);
        indent(json, 1).append("}");
    }

    private static void resolved(StringBuilder json, ResolvedJavaToolchain resolved) {
        indent(json, 1).append("\"resolved\": {\n");
        stringField(json, 2, "status", resolved.ok() ? "ok" : "error", true);
        stringField(json, 2, "source", resolved.source().label(), true);
        optionalPathField(json, 2, "javaHome", resolved.javaHome(), true);
        optionalPathField(json, 2, "java", resolved.java(), true);
        optionalPathField(json, 2, "javac", resolved.javac(), true);
        optionalPathField(json, 2, "jar", resolved.jar(), true);
        optionalPathField(json, 2, "nativeImage", resolved.nativeImage(), true);
        optionalStringField(json, 2, "version", resolved.runtime().version(), true);
        optionalStringField(json, 2, "vendor", resolved.runtime().vendor(), true);
        stringArrayField(json, 2, "problems", resolved.problems(), true);
        stringArrayField(json, 2, "notes", resolved.notes(), false);
        indent(json, 1).append("}");
    }

    private static void intField(StringBuilder json, int indent, String name, int value, boolean comma) {
        fieldPrefix(json, indent, name).append(value);
        fieldSuffix(json, comma);
    }

    private static void booleanField(StringBuilder json, int indent, String name, boolean value, boolean comma) {
        fieldPrefix(json, indent, name).append(value);
        fieldSuffix(json, comma);
    }

    private static void stringField(StringBuilder json, int indent, String name, String value, boolean comma) {
        fieldPrefix(json, indent, name).append(quote(value));
        fieldSuffix(json, comma);
    }

    private static void optionalPathField(
            StringBuilder json,
            int indent,
            String name,
            java.util.Optional<Path> value,
            boolean comma) {
        optionalStringField(json, indent, name, value.map(Path::toString), comma);
    }

    private static void optionalStringField(
            StringBuilder json,
            int indent,
            String name,
            java.util.Optional<String> value,
            boolean comma) {
        fieldPrefix(json, indent, name).append(value.map(ToolchainStatusJsonFormatter::quote).orElse("null"));
        fieldSuffix(json, comma);
    }

    private static void stringArrayField(
            StringBuilder json,
            int indent,
            String name,
            List<String> values,
            boolean comma) {
        fieldPrefix(json, indent, name).append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(", ");
            }
            json.append(quote(values.get(index)));
        }
        json.append(']');
        fieldSuffix(json, comma);
    }

    private static StringBuilder fieldPrefix(StringBuilder json, int indent, String name) {
        return indent(json, indent).append(quote(name)).append(": ");
    }

    private static void fieldSuffix(StringBuilder json, boolean comma) {
        if (comma) {
            json.append(',');
        }
        json.append('\n');
    }

    private static StringBuilder indent(StringBuilder json, int count) {
        return json.append("  ".repeat(count));
    }

    private static String quote(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}
