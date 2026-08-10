package sh.zolt.toml;

import java.util.ArrayList;
import java.util.List;

/** Source-line and assignment-span utilities used by preserving manifest edits. */
final class ManifestSourceText {
    private ManifestSourceText() {
    }

    static Span valueSpan(String line, int absoluteStart) {
        ScanMode mode = ScanMode.NORMAL;
        int equals = -1;
        int comment = line.length();
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            switch (mode) {
                case NORMAL -> {
                    if (character == '#') {
                        comment = index;
                        index = line.length();
                    } else if (character == '"') {
                        mode = ScanMode.BASIC;
                    } else if (character == '\'') {
                        mode = ScanMode.LITERAL;
                    } else if (character == '=' && equals < 0) {
                        equals = index;
                    }
                }
                case BASIC -> {
                    if (character == '\\') {
                        index++;
                    } else if (character == '"') {
                        mode = ScanMode.NORMAL;
                    }
                }
                case LITERAL -> {
                    if (character == '\'') {
                        mode = ScanMode.NORMAL;
                    }
                }
            }
        }
        if (equals < 0) {
            throw new ZoltConfigException(
                    "Could not safely edit zolt.toml because an assignment boundary was unavailable. No changes were written.");
        }
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

    record Span(int start, int end) {
    }

    private enum ScanMode {
        NORMAL,
        BASIC,
        LITERAL
    }

    static final class Lines {
        private final String source;
        private final int[] starts;

        Lines(String source) {
            this.source = source;
            List<Integer> offsets = new ArrayList<>();
            offsets.add(0);
            for (int index = 0; index < source.length(); index++) {
                if (source.charAt(index) == '\n' && index + 1 < source.length()) {
                    offsets.add(index + 1);
                }
            }
            this.starts = offsets.stream().mapToInt(Integer::intValue).toArray();
        }

        int count() {
            return starts.length;
        }

        int sourceLength() {
            return source.length();
        }

        int lineStart(int oneBasedLine) {
            if (oneBasedLine < 1 || oneBasedLine > starts.length) {
                throw new ZoltConfigException("Could not safely edit zolt.toml because a source line was unavailable.");
            }
            return starts[oneBasedLine - 1];
        }

        int lineEnd(int oneBasedLine) {
            int index = oneBasedLine - 1;
            return index + 1 < starts.length ? starts[index + 1] : source.length();
        }

        String line(int oneBasedLine) {
            int start = lineStart(oneBasedLine);
            int end = lineEnd(oneBasedLine);
            while (end > start && (source.charAt(end - 1) == '\n' || source.charAt(end - 1) == '\r')) {
                end--;
            }
            return source.substring(start, end);
        }

        boolean endsMidLine(int offset) {
            return offset > 0 && offset == source.length() && source.charAt(offset - 1) != '\n';
        }
    }
}
