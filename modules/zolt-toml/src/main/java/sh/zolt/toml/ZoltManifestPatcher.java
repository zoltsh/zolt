package sh.zolt.toml;

import sh.zolt.project.ProjectConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlPosition;
import org.tomlj.TomlTable;

/** Applies the modeled mutation delta without regenerating the user-owned manifest. */
final class ZoltManifestPatcher {
    private static final List<List<String>> EDITABLE_TABLES = List.of(
            List.of("versions"),
            List.of("platforms"),
            List.of("dependencyConstraints"),
            List.of("api", "dependencies"),
            List.of("dependencies"),
            List.of("runtime", "dependencies"),
            List.of("provided", "dependencies"),
            List.of("dev", "dependencies"),
            List.of("test", "dependencies"),
            List.of("annotationProcessors"),
            List.of("test", "annotationProcessors"));

    private ZoltManifestPatcher() {
    }

    static String patch(
            String source,
            ProjectConfig original,
            ProjectConfig updated,
            ZoltTomlWriter canonicalWriter) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(updated, "updated");
        Objects.requireNonNull(canonicalWriter, "canonicalWriter");

        CanonicalManifest before = CanonicalManifest.parse(canonicalWriter.write(original));
        CanonicalManifest after = CanonicalManifest.parse(canonicalWriter.write(updated));
        TomlParseResult parsedSource = Toml.parse(source);
        if (parsedSource.hasErrors()) {
            throw new ZoltConfigException("Could not edit zolt.toml because its source no longer parses.");
        }

        ManifestSourceText.Lines lines = new ManifestSourceText.Lines(source);
        List<TextEdit> edits = new ArrayList<>();
        Map<Integer, StringBuilder> insertions = new LinkedHashMap<>();
        StringBuilder appendedTables = new StringBuilder();
        String newline = source.contains("\r\n") ? "\r\n" : "\n";

        for (List<String> tablePath : EDITABLE_TABLES) {
            CanonicalSection oldSection = before.section(tablePath);
            CanonicalSection newSection = after.section(tablePath);
            Set<String> changedKeys = changedKeys(oldSection.entries(), newSection.entries());
            if (changedKeys.isEmpty()) {
                continue;
            }

            TomlTable sourceTable = parsedSource.getTable(tablePath);
            List<CanonicalEntry> additions = new ArrayList<>();
            for (String key : changedKeys) {
                CanonicalEntry desired = newSection.entries().get(key);
                TomlPosition sourcePosition = sourceTable == null
                        ? null
                        : sourceTable.inputPositionOf(List.of(key));
                if (sourcePosition == null) {
                    if (desired != null) {
                        additions.add(desired);
                    } else {
                        throw missingSourceEntry(tablePath, key);
                    }
                    continue;
                }

                int lineNumber = sourcePosition.line();
                if (desired == null) {
                    edits.add(new TextEdit(
                            lines.lineStart(lineNumber),
                            lines.lineEnd(lineNumber),
                            ""));
                } else {
                    ManifestSourceText.Span value = ManifestSourceText.valueSpan(
                            lines.line(lineNumber),
                            lines.lineStart(lineNumber));
                    edits.add(new TextEdit(value.start(), value.end(), desired.value()));
                }
            }

            if (!additions.isEmpty()) {
                additions.sort(Comparator.comparingInt(CanonicalEntry::order));
                String assignments = assignments(additions, newline);
                HeaderLocation header = findHeader(parsedSource, lines, tablePath);
                if (header != null) {
                    int insertionPoint = insertionPoint(sourceTable, lines, header);
                    String prefix = lines.endsMidLine(insertionPoint) ? newline : "";
                    insertions.computeIfAbsent(insertionPoint, ignored -> new StringBuilder())
                            .append(prefix)
                            .append(assignments);
                } else if (sourceTable != null) {
                    int insertionPoint = firstHeaderOffset(parsedSource, lines);
                    String dottedAssignments = dottedAssignments(tablePath, additions, newline);
                    insertions.computeIfAbsent(insertionPoint, ignored -> new StringBuilder())
                            .append(dottedAssignments)
                            .append(newline);
                } else {
                    appendTable(source, appendedTables, tablePath, assignments, newline);
                }
            }
        }

        for (Map.Entry<Integer, StringBuilder> insertion : insertions.entrySet()) {
            edits.add(new TextEdit(insertion.getKey(), insertion.getKey(), insertion.getValue().toString()));
        }
        if (!appendedTables.isEmpty()) {
            edits.add(new TextEdit(source.length(), source.length(), appendedTables.toString()));
        }
        return apply(source, edits);
    }

    private static Set<String> changedKeys(
            Map<String, CanonicalEntry> before,
            Map<String, CanonicalEntry> after) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        keys.removeIf(key -> Objects.equals(value(before.get(key)), value(after.get(key))));
        return keys;
    }

    private static String value(CanonicalEntry entry) {
        return entry == null ? null : entry.value();
    }

    private static ZoltConfigException missingSourceEntry(List<String> tablePath, String key) {
        return new ZoltConfigException(
                "Could not safely edit ["
                        + String.join(".", tablePath)
                        + "]."
                        + key
                        + " because its source position was unavailable. No changes were written.");
    }

    private static int insertionPoint(
            TomlTable table,
            ManifestSourceText.Lines lines,
            HeaderLocation header) {
        int lastLine = header.lineNumber();
        if (table != null) {
            for (String key : table.keySet()) {
                TomlPosition position = table.inputPositionOf(List.of(key));
                if (position != null) {
                    lastLine = Math.max(lastLine, position.line());
                }
            }
        }
        return lines.lineEnd(lastLine);
    }

    private static String assignments(List<CanonicalEntry> additions, String newline) {
        StringBuilder output = new StringBuilder();
        for (CanonicalEntry entry : additions) {
            output.append(entry.assignment()).append(newline);
        }
        return output.toString();
    }

    private static String dottedAssignments(
            List<String> tablePath,
            List<CanonicalEntry> additions,
            String newline) {
        String prefix = String.join(".", tablePath) + ".";
        StringBuilder output = new StringBuilder();
        for (CanonicalEntry entry : additions) {
            output.append(prefix).append(entry.assignment()).append(newline);
        }
        return output.toString();
    }

    private static void appendTable(
            String source,
            StringBuilder appended,
            List<String> tablePath,
            String assignments,
            String newline) {
        String existing = source + appended;
        if (!existing.isEmpty() && !existing.endsWith(newline)) {
            appended.append(newline);
            existing += newline;
        }
        if (!existing.isEmpty() && !existing.endsWith(newline + newline)) {
            appended.append(newline);
        }
        appended.append('[')
                .append(String.join(".", tablePath))
                .append(']')
                .append(newline)
                .append(assignments);
    }

    private static int firstHeaderOffset(
            TomlParseResult parsed,
            ManifestSourceText.Lines lines) {
        int first = lines.sourceLength();
        for (List<String> path : parsed.keyPathSet(true)) {
            TomlPosition position = parsed.inputPositionOf(path);
            if (position == null) {
                continue;
            }
            String candidate = lines.line(position.line()).stripLeading();
            if (candidate.startsWith("[")) {
                first = Math.min(first, lines.lineStart(position.line()));
            }
        }
        return first;
    }

    private static HeaderLocation findHeader(
            TomlParseResult parsed,
            ManifestSourceText.Lines lines,
            List<String> expectedPath) {
        TomlPosition position = parsed.inputPositionOf(expectedPath);
        if (position == null) {
            return null;
        }
        int line = position.line();
        String candidate = lines.line(line).stripLeading();
        if (!candidate.startsWith("[") || candidate.startsWith("[[")) {
            return null;
        }
        TomlParseResult probe = Toml.parse(candidate + "\n__zolt_manifest_probe__ = true\n");
        if (probe.hasErrors()) {
            return null;
        }
        TomlTable table = probe.getTable(expectedPath);
        if (table != null && Boolean.TRUE.equals(table.get("__zolt_manifest_probe__"))) {
            return new HeaderLocation(line);
        }
        return null;
    }

    private static String apply(String source, List<TextEdit> edits) {
        edits.sort(Comparator.comparingInt(TextEdit::start)
                .thenComparingInt(TextEdit::end)
                .reversed());
        int previousStart = source.length() + 1;
        StringBuilder result = new StringBuilder(source);
        for (TextEdit edit : edits) {
            if (edit.end() > previousStart) {
                throw new ZoltConfigException(
                        "Could not safely edit zolt.toml because the requested source patches overlap. No changes were written.");
            }
            result.replace(edit.start(), edit.end(), edit.replacement());
            previousStart = edit.start();
        }
        return result.toString();
    }

    private record CanonicalManifest(Map<List<String>, CanonicalSection> sections) {
        private static CanonicalManifest parse(String source) {
            TomlParseResult parsed = Toml.parse(source);
            ManifestSourceText.Lines lines = new ManifestSourceText.Lines(source);
            Map<List<String>, CanonicalSection> sections = new LinkedHashMap<>();
            for (List<String> path : EDITABLE_TABLES) {
                TomlTable table = parsed.getTable(path);
                Map<String, CanonicalEntry> entries = new LinkedHashMap<>();
                if (table != null) {
                    int order = 0;
                    for (String key : table.keySet()) {
                        TomlPosition position = table.inputPositionOf(List.of(key));
                        if (position == null) {
                            continue;
                        }
                        String assignment = lines.line(position.line());
                        ManifestSourceText.Span value = ManifestSourceText.valueSpan(assignment, 0);
                        entries.put(key, new CanonicalEntry(
                                assignment,
                                assignment.substring(value.start(), value.end()),
                                order++));
                    }
                }
                sections.put(path, new CanonicalSection(entries));
            }
            return new CanonicalManifest(sections);
        }

        private CanonicalSection section(List<String> path) {
            return sections.getOrDefault(path, new CanonicalSection(Map.of()));
        }
    }

    private record CanonicalSection(Map<String, CanonicalEntry> entries) {
    }

    private record CanonicalEntry(String assignment, String value, int order) {
    }

    private record HeaderLocation(int lineNumber) {
    }

    private record TextEdit(int start, int end, String replacement) {
    }

}
