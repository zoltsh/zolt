package sh.zolt.build.profile;

import sh.zolt.test.runtime.TestRunException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TestProfileMerger {
    private static final Pattern NUMBER_FIELD = Pattern.compile("\"%s\"\\s*:\\s*(-?\\d+)");
    private static final Pattern STRING_FIELD = Pattern.compile("\"%s\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private TestProfileMerger() {
    }

    public static void mergeWorkerProfiles(Path profileDirectory, List<String> workerIds) {
        if (profileDirectory == null || workerIds == null || workerIds.isEmpty()) {
            return;
        }
        Path profileRoot = profileDirectory.toAbsolutePath().normalize();
        mergeProfiles(
                profileRoot,
                TestProfileInputs.workerProfiles(
                        profileRoot,
                        workerIds));
    }

    public static void mergeProfiles(Path profileDirectory, List<Path> profileJsonFiles) {
        if (profileDirectory == null || profileJsonFiles == null || profileJsonFiles.isEmpty()) {
            return;
        }
        Path profileRoot = profileDirectory.toAbsolutePath().normalize();
        List<String> tests = new ArrayList<>();
        List<String> containers = new ArrayList<>();
        Summary summary = Summary.empty();
        Metadata metadata = Metadata.empty();
        for (Path profileJson : profileJsonFiles) {
            if (profileJson == null || !Files.exists(profileJson)) {
                continue;
            }
            try {
                String json = Files.readString(profileJson);
                validateProfile(profileJson, json);
                tests.addAll(entries(json, "tests"));
                containers.addAll(entries(json, "containers"));
                summary = summary.plus(summary(json));
                metadata = metadata.plus(Metadata.from(json));
            } catch (IOException exception) {
                throw new TestRunException("Could not read test profile " + profileJson + ".", exception);
            }
        }
        if (tests.isEmpty() && containers.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(profileRoot);
            Files.writeString(profileRoot.resolve("profile.json"), mergedJson(summary, metadata, tests, containers));
        } catch (IOException exception) {
            throw new TestRunException("Could not write merged test profile to " + profileRoot.resolve("profile.json") + ".", exception);
        }
    }

    private static void validateProfile(Path profileJson, String json) {
        if (number(json, "schemaVersion") != 1L) {
            throw new TestRunException("Test profile " + profileJson + " has unsupported schemaVersion; expected 1.");
        }
        if (arrayBody(json, "tests").isEmpty() || arrayBody(json, "containers").isEmpty()) {
            throw new TestRunException("Test profile " + profileJson + " is missing tests or containers arrays.");
        }
    }

    private static Summary summary(String json) {
        return new Summary(
                number(json, "testsFound"),
                number(json, "testsSucceeded"),
                number(json, "testsFailed"),
                number(json, "testsSkipped"),
                number(json, "testsAborted"),
                number(json, "durationMillis"));
    }

    private static List<String> entries(String json, String fieldName) {
        return arrayBody(json, fieldName).stream()
                .flatMap(body -> objectBodies(body).stream())
                .toList();
    }

    private static Optional<String> arrayBody(String json, String fieldName) {
        int field = json.indexOf("\"" + fieldName + "\"");
        if (field < 0) {
            return Optional.empty();
        }
        int open = json.indexOf('[', field);
        if (open < 0) {
            return Optional.empty();
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int index = open; index < json.length(); index++) {
            char ch = json.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '[') {
                depth++;
            } else if (ch == ']') {
                depth--;
                if (depth == 0) {
                    return Optional.of(json.substring(open + 1, index));
                }
            }
        }
        return Optional.empty();
    }

    private static List<String> objectBodies(String arrayBody) {
        List<String> objects = new ArrayList<>();
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        int open = -1;
        for (int index = 0; index < arrayBody.length(); index++) {
            char ch = arrayBody.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                if (depth == 0) {
                    open = index;
                }
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0 && open >= 0) {
                    objects.add(arrayBody.substring(open, index + 1));
                }
            }
        }
        return objects;
    }

    private static long number(String json, String fieldName) {
        Matcher matcher = Pattern.compile(String.format(NUMBER_FIELD.pattern(), Pattern.quote(fieldName))).matcher(json);
        if (!matcher.find()) {
            return 0L;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private static String mergedJson(Summary summary, Metadata metadata, List<String> tests, List<String> containers) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        field(json, 1, "schemaVersion", "1", true);
        field(json, 1, "runner", quote("zolt-junit-worker"), true);
        field(json, 1, "workerId", quote(""), true);
        field(json, 1, "projectRoot", quote(metadata.projectRoot()), true);
        field(json, 1, "project", quote(metadata.project()), true);
        field(json, 1, "member", quote(metadata.member()), true);
        field(json, 1, "suite", quote(metadata.suite()), true);
        field(json, 1, "shard", quote(metadata.shard()), true);
        json.append("  \"summary\": {\n");
        field(json, 2, "testsFound", Long.toString(summary.testsFound()), true);
        field(json, 2, "testsSucceeded", Long.toString(summary.testsSucceeded()), true);
        field(json, 2, "testsFailed", Long.toString(summary.testsFailed()), true);
        field(json, 2, "testsSkipped", Long.toString(summary.testsSkipped()), true);
        field(json, 2, "testsAborted", Long.toString(summary.testsAborted()), true);
        field(json, 2, "durationMillis", Long.toString(summary.durationMillis()), false);
        json.append("  },\n");
        entries(json, "tests", tests, true);
        entries(json, "containers", containers, false);
        json.append("}\n");
        return json.toString();
    }

    private static void entries(StringBuilder json, String fieldName, List<String> entries, boolean comma) {
        json.append("  \"").append(fieldName).append("\": [");
        if (!entries.isEmpty()) {
            json.append("\n");
            for (int index = 0; index < entries.size(); index++) {
                json.append(indent(entries.get(index), 2));
                if (index + 1 < entries.size()) {
                    json.append(",");
                }
                json.append("\n");
            }
            json.append("  ]");
        } else {
            json.append("]");
        }
        if (comma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static String indent(String text, int depth) {
        String indentation = "  ".repeat(depth);
        String[] lines = text.strip().split("\\R");
        int baseline = lines.length > 1 ? Math.max(0, leadingSpaces(lines[1]) - 2) : 0;
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                result.append("\n");
            }
            result.append(indentation).append(dropLeadingSpaces(lines[index], baseline));
        }
        return result.toString();
    }

    private static int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static String dropLeadingSpaces(String line, int count) {
        int index = 0;
        while (index < line.length() && index < count && line.charAt(index) == ' ') {
            index++;
        }
        return line.substring(index);
    }

    private static void field(StringBuilder json, int depth, String name, String value, boolean comma) {
        json.append("  ".repeat(depth)).append("\"").append(name).append("\": ").append(value);
        if (comma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String string(String json, String fieldName) {
        Matcher matcher = Pattern.compile(String.format(STRING_FIELD.pattern(), Pattern.quote(fieldName))).matcher(json);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private record Summary(
            long testsFound,
            long testsSucceeded,
            long testsFailed,
            long testsSkipped,
            long testsAborted,
            long durationMillis) {
        private static Summary empty() {
            return new Summary(0L, 0L, 0L, 0L, 0L, 0L);
        }

        private Summary plus(Summary other) {
            return new Summary(
                    testsFound + other.testsFound(),
                    testsSucceeded + other.testsSucceeded(),
                    testsFailed + other.testsFailed(),
                    testsSkipped + other.testsSkipped(),
                    testsAborted + other.testsAborted(),
                    durationMillis + other.durationMillis());
        }
    }

    private record Metadata(String projectRoot, String project, String member, String suite, String shard) {
        private static Metadata empty() {
            return new Metadata("", "", "", "", "");
        }

        private static Metadata from(String json) {
            return new Metadata(
                    string(json, "projectRoot"),
                    string(json, "project"),
                    string(json, "member"),
                    string(json, "suite"),
                    string(json, "shard"));
        }

        private Metadata plus(Metadata other) {
            return new Metadata(
                    common(projectRoot, other.projectRoot()),
                    common(project, other.project()),
                    common(member, other.member()),
                    common(suite, other.suite()),
                    common(shard, other.shard()));
        }

        private static String common(String left, String right) {
            if (left == null || left.isEmpty()) {
                return right == null ? "" : right;
            }
            if (right == null || right.isEmpty()) {
                return left;
            }
            return left.equals(right) ? left : "";
        }
    }
}
