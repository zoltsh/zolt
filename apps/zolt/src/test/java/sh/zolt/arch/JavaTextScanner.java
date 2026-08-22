package sh.zolt.arch;

/**
 * Extracts the author-facing text of a Java source file line by line: string literal contents, text
 * block bodies, and comment text. Everything else is code.
 *
 * <p>The scanner is stateful because text blocks and block comments span lines. Feeding a file's
 * lines in order to {@link #next(String)} keeps a {@code """}-delimited TOML fixture visible to the
 * removed-spelling gate, which a per-line regex cannot see.
 */
final class JavaTextScanner {
    private static final String TEXT_BLOCK = "\"\"\"";

    private boolean inTextBlock;
    private boolean inBlockComment;

    /** The author-facing text of the next line, given every line fed before it. */
    String next(String line) {
        StringBuilder text = new StringBuilder();
        int index = 0;
        while (index < line.length()) {
            if (inTextBlock) {
                index = continueTextBlock(line, index, text);
            } else if (inBlockComment) {
                index = continueBlockComment(line, index, text);
            } else {
                index = startSpan(line, index, text);
            }
        }
        return text.toString();
    }

    /**
     * Consumes text block body up to an unescaped closing delimiter. A text block escapes an inner
     * {@code """} as {@code \"""}, so escapes are honoured rather than read as the delimiter.
     */
    private int continueTextBlock(String line, int start, StringBuilder text) {
        int index = start;
        while (index < line.length()) {
            if (line.charAt(index) == '\\') {
                index += 2;
                continue;
            }
            if (line.startsWith(TEXT_BLOCK, index)) {
                append(text, line.substring(start, index));
                inTextBlock = false;
                return index + TEXT_BLOCK.length();
            }
            index++;
        }
        append(text, line.substring(start));
        return line.length();
    }

    private int continueBlockComment(String line, int start, StringBuilder text) {
        int end = line.indexOf("*/", start);
        if (end < 0) {
            append(text, line.substring(start));
            return line.length();
        }
        append(text, line.substring(start, end));
        inBlockComment = false;
        return end + 2;
    }

    /** Reads one token of ordinary code, capturing it when an author would read it. */
    private int startSpan(String line, int index, StringBuilder text) {
        if (line.startsWith(TEXT_BLOCK, index)) {
            inTextBlock = true;
            return index + TEXT_BLOCK.length();
        }
        if (line.startsWith("//", index)) {
            append(text, line.substring(index));
            return line.length();
        }
        if (line.startsWith("/*", index)) {
            inBlockComment = true;
            return index + 2;
        }
        char character = line.charAt(index);
        if (character != '"' && character != '\'') {
            return index + 1;
        }
        int end = endOfLiteral(line, index, character);
        if (character == '"') {
            append(text, line.substring(index, Math.min(end + 1, line.length())));
        }
        return end + 1;
    }

    private static int endOfLiteral(String line, int start, char quote) {
        int index = start + 1;
        while (index < line.length()) {
            char character = line.charAt(index);
            if (character == '\\') {
                index += 2;
                continue;
            }
            if (character == quote) {
                return index;
            }
            index++;
        }
        return line.length();
    }

    private static void append(StringBuilder text, String fragment) {
        if (!text.isEmpty()) {
            text.append(' ');
        }
        text.append(fragment);
    }
}
