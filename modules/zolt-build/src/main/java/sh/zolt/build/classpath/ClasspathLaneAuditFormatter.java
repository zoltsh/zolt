package sh.zolt.build.classpath;

import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathLanePolicy;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.dependency.DependencyScope;
import java.util.Comparator;
import java.util.List;

public final class ClasspathLaneAuditFormatter {
    public String formatText(ZoltLockfile lockfile) {
        StringBuilder output = new StringBuilder();
        output.append("Classpath lane audit\n\n");
        output.append("Lane policy:\n");
        output.append("scope               compile runtime test processor test-processor tool-spring-aot tool-openapi tool-protobuf tool-coverage package-default disposition\n");
        for (DependencyScope scope : scopes()) {
            output.append("%-19s %-7s %-7s %-4s %-9s %-14s %-15s %-12s %-13s %-13s %-15s %s%n".formatted(
                    scope.lockfileName(),
                    yesNo(scope.entersMainCompileClasspath()),
                    yesNo(scope.entersMainRuntimeClasspath()),
                    yesNo(scope.entersTestRuntimeClasspath()),
                    yesNo(scope.entersMainProcessorClasspath()),
                    yesNo(scope.entersTestProcessorClasspath()),
                    yesNo(scope == DependencyScope.TOOL_SPRING_AOT),
                    yesNo(scope == DependencyScope.TOOL_OPENAPI),
                    yesNo(scope == DependencyScope.TOOL_PROTOBUF),
                    yesNo(scope == DependencyScope.TOOL_COVERAGE),
                    yesNo(scope.packagedByDefault()),
                    ClasspathLanePolicy.disposition(scope)));
        }
        output.append('\n');
        output.append("Resolved packages:\n");
        List<LockPackage> packages = sortedPackages(lockfile);
        if (packages.isEmpty()) {
            output.append("No packages in zolt.lock.\n");
        } else {
            for (LockPackage lockPackage : packages) {
                output.append("- ")
                        .append(coordinate(lockPackage))
                        .append(" [")
                        .append(lockPackage.scope().lockfileName())
                        .append("] lanes=")
                        .append(String.join(",", ClasspathLanePolicy.lanes(lockPackage.scope())))
                        .append(" package=")
                        .append(ClasspathLanePolicy.disposition(lockPackage.scope()))
                        .append('\n');
            }
        }
        return output.toString();
    }

    public String formatJson(ZoltLockfile lockfile) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        field(json, 1, "schemaVersion", 1, true);
        stringField(json, 1, "command", "classpath audit", true);
        lanePolicy(json);
        comma(json);
        packages(json, lockfile);
        json.append("\n}\n");
        return json.toString();
    }

    private static void lanePolicy(StringBuilder json) {
        indent(json, 1).append("\"lanes\": [\n");
        List<DependencyScope> scopes = scopes();
        for (int index = 0; index < scopes.size(); index++) {
            DependencyScope scope = scopes.get(index);
            indent(json, 2).append("{\n");
            stringField(json, 3, "scope", scope.lockfileName(), true);
            field(json, 3, "compile", scope.entersMainCompileClasspath(), true);
            field(json, 3, "runtime", scope.entersMainRuntimeClasspath(), true);
            field(json, 3, "test", scope.entersTestRuntimeClasspath(), true);
            field(json, 3, "processor", scope.entersMainProcessorClasspath(), true);
            field(json, 3, "testProcessor", scope.entersTestProcessorClasspath(), true);
            field(json, 3, "toolSpringAot", scope == DependencyScope.TOOL_SPRING_AOT, true);
            field(json, 3, "toolOpenapi", scope == DependencyScope.TOOL_OPENAPI, true);
            field(json, 3, "toolProtobuf", scope == DependencyScope.TOOL_PROTOBUF, true);
            field(json, 3, "toolCoverage", scope == DependencyScope.TOOL_COVERAGE, true);
            field(json, 3, "packageDefault", scope.packagedByDefault(), true);
            stringField(json, 3, "disposition", ClasspathLanePolicy.disposition(scope), false);
            indent(json, 2).append("}");
            if (index + 1 < scopes.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        indent(json, 1).append("]");
    }

    private static void packages(StringBuilder json, ZoltLockfile lockfile) {
        indent(json, 1).append("\"packages\": [");
        List<LockPackage> packages = sortedPackages(lockfile);
        if (!packages.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < packages.size(); index++) {
                LockPackage lockPackage = packages.get(index);
                indent(json, 2).append("{\n");
                stringField(json, 3, "coordinate", coordinate(lockPackage), true);
                stringField(json, 3, "scope", lockPackage.scope().lockfileName(), true);
                field(json, 3, "direct", lockPackage.direct(), true);
                stringArrayField(json, 3, "lanes", ClasspathLanePolicy.lanes(lockPackage.scope()), true);
                field(json, 3, "packageDefault", lockPackage.scope().packagedByDefault(), true);
                stringField(json, 3, "disposition", ClasspathLanePolicy.disposition(lockPackage.scope()), false);
                indent(json, 2).append("}");
                if (index + 1 < packages.size()) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, 1);
        }
        json.append("]");
    }

    private static List<DependencyScope> scopes() {
        return List.of(DependencyScope.values());
    }

    private static List<LockPackage> sortedPackages(ZoltLockfile lockfile) {
        return lockfile.packages().stream()
                .sorted(Comparator.comparing(ClasspathLaneAuditFormatter::coordinate))
                .toList();
    }

    private static String coordinate(LockPackage lockPackage) {
        return lockPackage.packageId() + ":" + lockPackage.version();
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
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

    private static void field(StringBuilder json, int level, String name, int value, boolean trailingComma) {
        indent(json, level);
        string(json, name);
        json.append(": ").append(value);
        if (trailingComma) {
            json.append(',');
        }
        json.append('\n');
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

    private static void comma(StringBuilder json) {
        json.append(",\n");
    }
}
