package sh.zolt.toml.manifest;

/** Finds the exact end of a TOML value after Tomlj has already validated the document. */
final class TomlValueSpanScanner {
    private final String source;
    private final int length;

    TomlValueSpanScanner(String source) {
        this.source = source;
        this.length = source.length();
    }

    Result scan(int start) {
        int cursor = start;
        int squareDepth = 0;
        int braceDepth = 0;
        Quote quote = Quote.NONE;
        while (cursor < length) {
            char character = source.charAt(cursor);
            if (quote == Quote.BASIC) {
                if (character == '\\') {
                    cursor = skipEscapedCharacter(cursor);
                } else {
                    if (character == '"') {
                        quote = Quote.NONE;
                    }
                    cursor++;
                }
                continue;
            }
            if (quote == Quote.LITERAL) {
                if (character == '\'') {
                    quote = Quote.NONE;
                }
                cursor++;
                continue;
            }
            if (quote == Quote.MULTILINE_BASIC) {
                if (character == '\\') {
                    cursor = skipEscapedCharacter(cursor);
                } else if (character == '"') {
                    int runEnd = quoteRunEnd(cursor, '"');
                    if (runEnd - cursor >= 3) {
                        quote = Quote.NONE;
                    }
                    cursor = runEnd;
                } else {
                    cursor++;
                }
                continue;
            }
            if (quote == Quote.MULTILINE_LITERAL) {
                if (character == '\'') {
                    int runEnd = quoteRunEnd(cursor, '\'');
                    if (runEnd - cursor >= 3) {
                        quote = Quote.NONE;
                    }
                    cursor = runEnd;
                } else {
                    cursor++;
                }
                continue;
            }

            if (character == '"') {
                boolean multiline = source.startsWith("\"\"\"", cursor);
                quote = multiline ? Quote.MULTILINE_BASIC : Quote.BASIC;
                cursor += multiline ? 3 : 1;
            } else if (character == '\'') {
                boolean multiline = source.startsWith("'''", cursor);
                quote = multiline ? Quote.MULTILINE_LITERAL : Quote.LITERAL;
                cursor += multiline ? 3 : 1;
            } else if (character == '[') {
                squareDepth++;
                cursor++;
            } else if (character == ']') {
                if (--squareDepth < 0) {
                    throw fail(cursor, "array value closed outside an array");
                }
                cursor++;
            } else if (character == '{') {
                braceDepth++;
                cursor++;
            } else if (character == '}') {
                if (--braceDepth < 0) {
                    throw fail(cursor, "inline-table value closed outside a table");
                }
                cursor++;
            } else if (character == '#') {
                if (squareDepth > 0) {
                    cursor = lineContentEnd(cursor);
                } else if (braceDepth > 0) {
                    throw fail(cursor, "inline-table comment made its value span ambiguous");
                } else {
                    int valueEnd = trimHorizontalEnd(start, cursor);
                    int commentEnd = lineContentEnd(cursor);
                    int lineEnd = commentEnd < length ? newlineEnd(commentEnd) : length;
                    return result(start, valueEnd, cursor, commentEnd, lineEnd);
                }
            } else if (isNewline(cursor)) {
                if (squareDepth > 0) {
                    cursor = newlineEnd(cursor);
                } else if (braceDepth > 0) {
                    throw fail(cursor, "inline-table value crossed a physical line");
                } else {
                    int valueEnd = trimHorizontalEnd(start, cursor);
                    return result(start, valueEnd, -1, -1, newlineEnd(cursor));
                }
            } else {
                cursor++;
            }
        }
        if (quote != Quote.NONE || squareDepth != 0 || braceDepth != 0) {
            throw fail(start, "value terminator was unavailable");
        }
        return result(start, trimHorizontalEnd(start, length), -1, -1, length);
    }

    private Result result(int start, int valueEnd, int commentStart, int commentEnd, int lineEnd) {
        if (valueEnd <= start) {
            throw fail(start, "assignment value span was empty");
        }
        return new Result(valueEnd, commentStart, commentEnd, lineEnd);
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

    private int quoteRunEnd(int start, char quote) {
        int cursor = start;
        while (cursor < length && source.charAt(cursor) == quote) {
            cursor++;
        }
        return cursor;
    }

    private static TomlSourceScanner.ScanException fail(int offset, String message) {
        return TomlSourceScanner.fail(offset, message);
    }

    record Result(
            int valueEnd,
            int trailingCommentStart,
            int commentEnd,
            int lineEnd) {}

    private enum Quote {
        NONE,
        BASIC,
        LITERAL,
        MULTILINE_BASIC,
        MULTILINE_LITERAL
    }
}
