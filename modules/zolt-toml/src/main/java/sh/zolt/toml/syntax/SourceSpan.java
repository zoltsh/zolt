package sh.zolt.toml.syntax;

import java.util.Objects;

/** A half-open source range measured in UTF-16 code units, as used by {@link String}. */
public record SourceSpan(int start, int end) {
    public SourceSpan {
        if (start < 0) {
            throw new IllegalArgumentException("Source span start must not be negative.");
        }
        if (end < start) {
            throw new IllegalArgumentException("Source span end must not precede its start.");
        }
    }

    public static SourceSpan emptyAt(int offset) {
        return new SourceSpan(offset, offset);
    }

    public int length() {
        return end - start;
    }

    public boolean isEmpty() {
        return start == end;
    }

    public String text(String source) {
        Objects.requireNonNull(source, "source");
        if (end > source.length()) {
            throw new IllegalArgumentException("Source span exceeds the source length.");
        }
        return source.substring(start, end);
    }
}
