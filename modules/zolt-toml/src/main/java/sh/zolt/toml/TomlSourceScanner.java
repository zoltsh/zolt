package sh.zolt.toml;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.tomlj.Toml;
import sh.zolt.toml.TomlSyntaxNodeBuilders.Assignment;
import sh.zolt.toml.TomlSyntaxNodeBuilders.Header;
import sh.zolt.toml.TomlSyntaxNodeBuilders.TableBuilder;

/** A source scanner applied only after Tomlj has accepted the document as TOML 1.0. */
final class TomlSourceScanner {
    private final String source;
    private final int length;
    private final List<TableBuilder> tables = new ArrayList<>();
    private final List<AssignmentSyntax> assignments = new ArrayList<>();
    private final Set<List<String>> knownTables = new HashSet<>();
    private final TomlValueSpanScanner valueScanner;
    private List<String> currentTablePath = List.of();
    private TableBuilder currentPhysicalTable;
    private int tableOrder;
    private int assignmentOrder;

    TomlSourceScanner(String source) {
        this.source = source;
        this.length = source.length();
        this.valueScanner = new TomlValueSpanScanner(source);
    }

    Result scan() {
        currentPhysicalTable = addImplicitTable(List.of(), 0, true);
        int cursor = 0;
        while (cursor < length) {
            int lineStart = cursor;
            int statementStart = skipHorizontal(cursor);
            if (statementStart == length) {
                cursor = length;
            } else if (isNewline(statementStart)) {
                cursor = newlineEnd(statementStart);
            } else if (source.charAt(statementStart) == '#') {
                cursor = physicalLineEnd(statementStart);
            } else if (source.charAt(statementStart) == '[') {
                Header header = scanHeader(statementStart);
                currentPhysicalTable.closeBodyAt(lineStart);
                addImplicitParents(header.path(), statementStart);
                currentPhysicalTable = addExplicitTable(header);
                currentTablePath = header.path();
                cursor = header.lineEnd();
            } else {
                Assignment assignment = scanAssignment(statementStart, lineStart);
                addImplicitAssignmentParents(assignment.keyPath(), statementStart);
                assignments.add(assignment.syntax(assignmentOrder++, currentTablePath));
                cursor = assignment.lineEnd();
            }
        }
        currentPhysicalTable.closeBodyAt(length);
        return new Result(
                tables.stream().map(TableBuilder::syntax).toList(),
                List.copyOf(assignments));
    }

    private Header scanHeader(int start) {
        boolean arrayTable = startsWith(start, "[[");
        int openingWidth = arrayTable ? 2 : 1;
        int contentStart = start + openingWidth;
        int cursor = contentStart;
        KeyQuote quote = KeyQuote.NONE;
        int closingStart = -1;

        while (cursor < length && !isNewline(cursor)) {
            char character = source.charAt(cursor);
            if (quote == KeyQuote.BASIC) {
                if (character == '\\') {
                    cursor = skipEscapedCharacter(cursor);
                } else {
                    if (character == '"') {
                        quote = KeyQuote.NONE;
                    }
                    cursor++;
                }
            } else if (quote == KeyQuote.LITERAL) {
                if (character == '\'') {
                    quote = KeyQuote.NONE;
                }
                cursor++;
            } else if (character == '"') {
                quote = KeyQuote.BASIC;
                cursor++;
            } else if (character == '\'') {
                quote = KeyQuote.LITERAL;
                cursor++;
            } else if (character == ']' && (!arrayTable || startsWith(cursor, "]]"))) {
                closingStart = cursor;
                break;
            } else {
                cursor++;
            }
        }
        if (closingStart < 0 || quote != KeyQuote.NONE) {
            throw fail(start, "table header terminator was unavailable");
        }

        int headerEnd = closingStart + openingWidth;
        int suffix = skipHorizontal(headerEnd);
        if (suffix < length && source.charAt(suffix) == '#') {
            suffix = lineContentEnd(suffix);
        }
        if (suffix < length && !isNewline(suffix)) {
            throw fail(suffix, "unexpected source followed a table header");
        }
        int lineEnd = suffix < length ? newlineEnd(suffix) : length;
        List<String> path = parseKey(source.substring(contentStart, closingStart), contentStart);
        return new Header(path, new SourceSpan(start, headerEnd), arrayTable, lineEnd);
    }

    private Assignment scanAssignment(int keyStart, int lineStart) {
        int equals = assignmentEquals(keyStart);
        int keyEnd = trimHorizontalEnd(keyStart, equals);
        if (keyEnd == keyStart) {
            throw fail(keyStart, "assignment key span was empty");
        }
        List<String> keyPath = parseKey(source.substring(keyStart, keyEnd), keyStart);
        int valueStart = skipHorizontal(equals + 1);
        if (valueStart == length || isNewline(valueStart) || source.charAt(valueStart) == '#') {
            throw fail(valueStart, "assignment value span was empty");
        }
        TomlValueSpanScanner.Result value = valueScanner.scan(valueStart);
        SourceSpan keySpan = new SourceSpan(keyStart, keyEnd);
        SourceSpan valueSpan = new SourceSpan(valueStart, value.valueEnd());
        return new Assignment(
                keyPath,
                keySpan,
                valueSpan,
                new SourceSpan(keyStart, value.valueEnd()),
                new SourceSpan(lineStart, value.lineEnd()),
                value.trailingCommentStart() < 0
                        ? Optional.empty()
                        : Optional.of(new SourceSpan(value.trailingCommentStart(), value.commentEnd())),
                value.lineEnd());
    }

    private int assignmentEquals(int start) {
        int cursor = start;
        KeyQuote quote = KeyQuote.NONE;
        while (cursor < length && !isNewline(cursor)) {
            char character = source.charAt(cursor);
            if (quote == KeyQuote.BASIC) {
                if (character == '\\') {
                    cursor = skipEscapedCharacter(cursor);
                } else {
                    if (character == '"') {
                        quote = KeyQuote.NONE;
                    }
                    cursor++;
                }
            } else if (quote == KeyQuote.LITERAL) {
                if (character == '\'') {
                    quote = KeyQuote.NONE;
                }
                cursor++;
            } else if (character == '"') {
                quote = KeyQuote.BASIC;
                cursor++;
            } else if (character == '\'') {
                quote = KeyQuote.LITERAL;
                cursor++;
            } else if (character == '=') {
                return cursor;
            } else {
                cursor++;
            }
        }
        throw fail(start, "assignment separator was unavailable");
    }

    private void addImplicitParents(List<String> path, int anchor) {
        for (int size = 1; size < path.size(); size++) {
            addImplicitTable(path.subList(0, size), anchor, false);
        }
    }

    private void addImplicitAssignmentParents(List<String> keyPath, int anchor) {
        ArrayList<String> fullPath = new ArrayList<>(currentTablePath);
        for (int index = 0; index < keyPath.size() - 1; index++) {
            fullPath.add(keyPath.get(index));
            addImplicitTable(fullPath, anchor, false);
        }
    }

    private TableBuilder addImplicitTable(List<String> path, int anchor, boolean root) {
        List<String> immutablePath = List.copyOf(path);
        if (!root && !knownTables.add(immutablePath)) {
            return null;
        }
        if (root) {
            knownTables.add(immutablePath);
        }
        TableBuilder table = TableBuilder.implicit(immutablePath, anchor, tableOrder++);
        tables.add(table);
        return table;
    }

    private TableBuilder addExplicitTable(Header header) {
        knownTables.add(header.path());
        TableBuilder table = TableBuilder.explicit(
                header.path(),
                header.headerSpan(),
                header.lineEnd(),
                header.arrayTable(),
                tableOrder++);
        tables.add(table);
        return table;
    }

    private List<String> parseKey(String rawKey, int offset) {
        try {
            List<String> path = List.copyOf(Toml.parseDottedKey(rawKey));
            if (path.isEmpty()) {
                throw fail(offset, "key path was empty");
            }
            return path;
        } catch (ScanException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw fail(offset, "key path could not be decoded");
        }
    }

    private int skipHorizontal(int offset) {
        int cursor = offset;
        while (cursor < length) {
            char character = source.charAt(cursor);
            if (character != ' ' && character != '\t') {
                break;
            }
            cursor++;
        }
        return cursor;
    }

    private int trimHorizontalEnd(int lowerBound, int end) {
        int cursor = end;
        while (cursor > lowerBound) {
            char character = source.charAt(cursor - 1);
            if (character != ' ' && character != '\t') {
                break;
            }
            cursor--;
        }
        return cursor;
    }

    private int lineContentEnd(int offset) {
        int cursor = offset;
        while (cursor < length && !isNewline(cursor)) {
            cursor++;
        }
        return cursor;
    }

    private int physicalLineEnd(int offset) {
        int contentEnd = lineContentEnd(offset);
        return contentEnd < length ? newlineEnd(contentEnd) : length;
    }

    private int newlineEnd(int offset) {
        if (source.charAt(offset) == '\r' && offset + 1 < length && source.charAt(offset + 1) == '\n') {
            return offset + 2;
        }
        return offset + 1;
    }

    private boolean isNewline(int offset) {
        char character = source.charAt(offset);
        return character == '\n' || character == '\r';
    }

    private int skipEscapedCharacter(int slash) {
        if (slash + 1 >= length) {
            throw fail(slash, "escaped character was unavailable");
        }
        return slash + 2;
    }

    private boolean startsWith(int offset, String candidate) {
        return source.startsWith(candidate, offset);
    }

    static ScanException fail(int offset, String message) {
        return new ScanException(offset, message);
    }

    record Result(List<TableSyntax> tables, List<AssignmentSyntax> assignments) {
        Result {
            tables = List.copyOf(tables);
            assignments = List.copyOf(assignments);
        }
    }

    static final class ScanException extends RuntimeException {
        private final int offset;

        ScanException(int offset, String message) {
            super(message);
            this.offset = offset;
        }

        int offset() {
            return offset;
        }
    }

    private enum KeyQuote {
        NONE,
        BASIC,
        LITERAL
    }

}
