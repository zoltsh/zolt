package sh.zolt.project;

import java.util.Objects;
import java.util.regex.Pattern;

/** One exact installable Zolt product version, never a channel or selector. */
public record ZoltVersion(String value) {
    private static final Pattern EXACT = Pattern.compile(
            "^([0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z._-]*)?"
                    + "|[0-9A-Za-z._-]+-(nightly|zap)\\.[0-9]{8}\\.[0-9A-Fa-f]{7,40})$");

    public ZoltVersion {
        Objects.requireNonNull(value, "Zolt version must not be null.");
        if (!EXACT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid Zolt version `" + value + "`: use one exact version such as `0.1.0` or "
                            + "`0.1.0-SNAPSHOT`, not a channel, range, or dynamic selector.");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
