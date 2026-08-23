package sh.zolt.arch;

/**
 * Strips comments and string literals from Java source so an architecture scanner matches CODE, not
 * prose. Without this, a javadoc sentence naming the very rule it documents reads as a violation of
 * it, and guardrails that punish accurate documentation get their documentation deleted.
 *
 * <p>Replacement preserves length and line structure — every removed character becomes a space,
 * newlines survive — so a match's offset still points at the right place in the original file.
 */
final class JavaSourceCode {
    private JavaSourceCode() {
    }

    static String withoutCommentsAndStrings(String source) {
        StringBuilder code = new StringBuilder(source.length());
        State state = State.CODE;
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            switch (state) {
                case CODE -> {
                    if (current == '/' && next == '/') {
                        state = State.LINE_COMMENT;
                        code.append("  ");
                        index += 2;
                        continue;
                    }
                    if (current == '/' && next == '*') {
                        state = State.BLOCK_COMMENT;
                        code.append("  ");
                        index += 2;
                        continue;
                    }
                    if (current == '"' && next == '"' && charAt(source, index + 2) == '"') {
                        state = State.TEXT_BLOCK;
                        code.append("   ");
                        index += 3;
                        continue;
                    }
                    if (current == '"') {
                        state = State.STRING;
                        code.append(' ');
                        index++;
                        continue;
                    }
                    if (current == '\'') {
                        state = State.CHAR;
                        code.append(' ');
                        index++;
                        continue;
                    }
                    code.append(current);
                    index++;
                }
                case LINE_COMMENT -> {
                    if (current == '\n') {
                        state = State.CODE;
                        code.append(current);
                    } else {
                        code.append(' ');
                    }
                    index++;
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        state = State.CODE;
                        code.append("  ");
                        index += 2;
                        continue;
                    }
                    code.append(current == '\n' ? '\n' : ' ');
                    index++;
                }
                case STRING, CHAR -> {
                    char terminator = state == State.STRING ? '"' : '\'';
                    if (current == '\\') {
                        code.append("  ");
                        index += 2;
                        continue;
                    }
                    if (current == terminator || current == '\n') {
                        state = State.CODE;
                    }
                    code.append(current == '\n' ? '\n' : ' ');
                    index++;
                }
                case TEXT_BLOCK -> {
                    if (current == '\\') {
                        code.append("  ");
                        index += 2;
                        continue;
                    }
                    if (current == '"' && next == '"' && charAt(source, index + 2) == '"') {
                        state = State.CODE;
                        code.append("   ");
                        index += 3;
                        continue;
                    }
                    code.append(current == '\n' ? '\n' : ' ');
                    index++;
                }
                default -> throw new IllegalStateException("Unhandled scanner state " + state + ".");
            }
        }
        return code.toString();
    }

    private static char charAt(String source, int index) {
        return index < source.length() ? source.charAt(index) : '\0';
    }

    private enum State {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        CHAR,
        TEXT_BLOCK
    }
}
