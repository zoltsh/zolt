package sh.zolt.workspace.toml;

import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.WorkspaceConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlPosition;
import org.tomlj.TomlTable;

/** Applies existing workspace-root platform updates without regenerating the manifest. */
final class WorkspaceManifestPatcher {
    private WorkspaceManifestPatcher() {
    }

    static String patchPlatforms(String source, WorkspaceConfig original, WorkspaceConfig updated) {
        Set<String> changed = changedKeys(original.platforms(), updated.platforms());
        if (changed.isEmpty()) {
            return source;
        }
        TomlParseResult parsed = Toml.parse(source);
        TomlTable table = parsed.hasErrors() ? null : parsed.getTable("platforms");
        if (table == null) {
            throw unsupportedChange("<table>");
        }

        Lines lines = new Lines(source);
        List<TextEdit> edits = new ArrayList<>();
        for (String key : changed) {
            String before = original.platforms().get(key);
            String after = updated.platforms().get(key);
            TomlPosition position = table.inputPositionOf(List.of(key));
            if (before == null || after == null || position == null) {
                throw unsupportedChange(key);
            }
            int lineNumber = position.line();
            String line = lines.line(lineNumber);
            if (!isOneLiteralAssignment(line, key)) {
                throw unsupportedChange(key);
            }
            Span span = valueSpan(line, lines.lineStart(lineNumber));
            edits.add(new TextEdit(span.start(), span.end(), quote(after)));
        }
        edits.sort(Comparator.comparingInt(TextEdit::start).reversed());
        StringBuilder patched = new StringBuilder(source);
        for (TextEdit edit : edits) {
            patched.replace(edit.start(), edit.end(), edit.replacement());
        }
        return patched.toString();
    }

    private static boolean isOneLiteralAssignment(String line, String key) {
        TomlParseResult probe = Toml.parse("[platforms]\n" + line + "\n");
        TomlTable table = probe.hasErrors() ? null : probe.getTable("platforms");
        return table != null && table.get(List.of(key)) instanceof String;
    }

    private static Span valueSpan(String line, int absoluteStart) {
        int equals = assignmentOffset(line);
        if (equals < 0) {
            throw unsupportedChange("<assignment>");
        }
        int comment = commentOffset(line, equals + 1);
        int start = equals + 1;
        while (start < comment && Character.isWhitespace(line.charAt(start))) {
            start++;
        }
        int end = comment;
        while (end > start && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return new Span(absoluteStart + start, absoluteStart + end);
    }

    private static int assignmentOffset(String line) {
        return unquotedOffset(line, 0, '=');
    }

    private static int commentOffset(String line, int start) {
        int comment = unquotedOffset(line, start, '#');
        return comment < 0 ? line.length() : comment;
    }

    private static int unquotedOffset(String line, int start, char sought) {
        boolean escaped = false;
        char quote = 0;
        for (int index = start; index < line.length(); index++) {
            char character = line.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (character == '\\' && quote == '"') {
                escaped = true;
            } else if (quote != 0 && character == quote) {
                quote = 0;
            } else if (quote == 0 && (character == '"' || character == '\'')) {
                quote = character;
            } else if (quote == 0 && character == sought) {
                return index;
            }
        }
        return -1;
    }

    private static Set<String> changedKeys(Map<String, String> before, Map<String, String> after) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        keys.removeIf(key -> Objects.equals(before.get(key), after.get(key)));
        return keys;
    }

    private static ZoltConfigException unsupportedChange(String key) {
        return new ZoltConfigException(
                "Could not safely edit [platforms]." + key
                        + " in the workspace manifest. Keep the platform as one literal assignment and retry. No changes were written.");
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

    private record Lines(String source, int[] starts) {
        Lines(String source) {
            this(source, starts(source));
        }

        int lineStart(int oneBasedLine) {
            return starts[oneBasedLine - 1];
        }

        String line(int oneBasedLine) {
            int start = lineStart(oneBasedLine);
            int end = oneBasedLine < starts.length ? starts[oneBasedLine] : source.length();
            while (end > start && (source.charAt(end - 1) == '\n' || source.charAt(end - 1) == '\r')) {
                end--;
            }
            return source.substring(start, end);
        }

        private static int[] starts(String source) {
            List<Integer> offsets = new ArrayList<>();
            offsets.add(0);
            for (int index = 0; index < source.length(); index++) {
                if (source.charAt(index) == '\n' && index + 1 < source.length()) {
                    offsets.add(index + 1);
                }
            }
            return offsets.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    private record Span(int start, int end) {
    }

    private record TextEdit(int start, int end, String replacement) {
    }
}
