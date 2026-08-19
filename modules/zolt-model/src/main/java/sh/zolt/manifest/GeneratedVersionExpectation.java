package sh.zolt.manifest;

import java.util.Objects;
import java.util.regex.Pattern;

/** The deliberately small AND-only comparator grammar for process-tool probes. */
public record GeneratedVersionExpectation(String value) {
    private static final String TERM = "(?:>=|<=|==|!=|>|<|=)[0-9]+(?:\\.[0-9]+)*";
    private static final Pattern GRAMMAR = Pattern.compile(TERM + "(?: " + TERM + ")*");

    public GeneratedVersionExpectation {
        Objects.requireNonNull(value, "Generated process version expectation must not be null.");
        if (!GRAMMAR.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid generated process version expectation `" + value
                            + "`: use space-separated numeric comparator terms.");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
