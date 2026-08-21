package sh.zolt.cli.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record CommandErrorBlock(String summary, List<ContextRow> contextRows, Optional<String> next) {
    private static final Pattern COORDINATE =
            Pattern.compile("([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+)");

    static CommandErrorBlock from(String displayMessage) {
        String message = displayMessage == null || displayMessage.isBlank()
                ? "Command failed."
                : displayMessage.trim();
        SplitMessage split = split(message);
        return new CommandErrorBlock(split.summary(), contextRows(split.summary()), split.next());
    }

    static CommandErrorBlock of(String summary, String remediation) {
        String trimmedSummary = summary == null ? "" : summary.trim();
        String trimmedRemediation = remediation == null ? "" : remediation.trim();
        return new CommandErrorBlock(
                trimmedSummary,
                contextRows(trimmedSummary),
                trimmedRemediation.isBlank() ? Optional.empty() : Optional.of(trimmedRemediation));
    }

    private static SplitMessage split(String message) {
        int index = remediationIndex(message);
        if (index < 0) {
            return new SplitMessage(message, Optional.empty());
        }
        String summary = message.substring(0, index).trim();
        String next = message.substring(index).trim();
        if (!summary.endsWith(".") && !summary.endsWith("?") && !summary.endsWith("!")) {
            summary = summary + ".";
        }
        return new SplitMessage(summary, Optional.of(next));
    }

    private static int remediationIndex(String message) {
        List<String> starters = List.of(
                " Check that ",
                " Check the ",
                " Run `",
                " Run the ",
                " Use ",
                " Remove ",
                " Fix ",
                " Create ",
                " Move ",
                " Add ",
                " Edit ",
                " Set ",
                " Install ",
                " Delete ");
        int best = -1;
        for (String starter : starters) {
            int found = message.indexOf(starter);
            if (found >= 0 && (best < 0 || found < best)) {
                best = found;
            }
        }
        return best;
    }

    private static List<ContextRow> contextRows(String summary) {
        List<ContextRow> rows = new ArrayList<>();
        pathAfter(summary, "zolt.toml at ").ifPresent(path -> rows.add(new ContextRow("File", path)));
        pathAfter(summary, "zolt.lock at ").ifPresent(path -> rows.add(new ContextRow("File", path)));
        if (rows.stream().noneMatch(row -> "File".equals(row.label()))) {
            if (summary.contains("zolt.toml")) {
                rows.add(new ContextRow("File", "zolt.toml"));
            } else if (summary.contains("zolt.lock")) {
                rows.add(new ContextRow("File", "zolt.lock"));
            }
        }
        bracketAfter(summary, "Unknown field ").ifPresent(field -> rows.add(new ContextRow("Field", field)));
        bracketAfter(summary, "Unknown top-level section ").ifPresent(section -> rows.add(new ContextRow("Section", section)));
        quotedAfter(summary, "Unknown manifest field ").ifPresent(field -> rows.add(new ContextRow("Field", field)));
        quotedAfter(summary, "Unknown manifest section ")
                .ifPresent(section -> rows.add(new ContextRow("Section", section)));
        backtickAfter(summary, "Unsupported ").ifPresent(value -> rows.add(new ContextRow("Unsupported", value)));
        coordinate(summary).ifPresent(coordinate -> rows.add(new ContextRow("Coordinate", coordinate)));
        return rows;
    }

    private static Optional<String> pathAfter(String text, String marker) {
        int start = text.indexOf(marker);
        if (start < 0) {
            return Optional.empty();
        }
        start += marker.length();
        int end = text.indexOf(". ", start);
        if (end < 0) {
            end = text.length();
        }
        String path = stripTrailingPunctuation(text.substring(start, end).trim());
        return path.isBlank() ? Optional.empty() : Optional.of(path);
    }

    private static Optional<String> bracketAfter(String text, String marker) {
        int start = text.indexOf(marker);
        if (start < 0) {
            return Optional.empty();
        }
        start = text.indexOf('[', start);
        int end = text.indexOf(']', start);
        if (start < 0 || end < 0) {
            return Optional.empty();
        }
        while (end + 1 < text.length() && !Character.isWhitespace(text.charAt(end + 1))) {
            end++;
        }
        return Optional.of(stripTrailingPunctuation(text.substring(start, end + 1)));
    }

    /** The manifest schema quotes the offending name in backticks; the row shows it unquoted. */
    private static Optional<String> quotedAfter(String text, String marker) {
        return backtickAfter(text, marker).map(value -> value.replace("`", ""));
    }

    private static Optional<String> backtickAfter(String text, String marker) {
        int start = text.indexOf(marker);
        if (start < 0) {
            return Optional.empty();
        }
        start = text.indexOf('`', start);
        int end = text.indexOf('`', start + 1);
        if (start < 0 || end < 0) {
            return Optional.empty();
        }
        return Optional.of(text.substring(start, end + 1));
    }

    private static Optional<String> coordinate(String text) {
        // Only run the coordinate heuristic over a single-line message. Raw compiler output (e.g. javac
        // diagnostics) reaches this factory as a multiline summary; scanning it would scrape unrelated
        // colon-joined tokens (-Xlint:-options, file:line) into a false Coordinate row.
        if (text.indexOf('\n') >= 0) {
            return Optional.empty();
        }
        Matcher matcher = COORDINATE.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static String stripTrailingPunctuation(String value) {
        while (value.endsWith(".") || value.endsWith(",")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    record ContextRow(String label, String value) {
    }

    private record SplitMessage(String summary, Optional<String> next) {
    }
}
