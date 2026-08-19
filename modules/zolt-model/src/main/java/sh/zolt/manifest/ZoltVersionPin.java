package sh.zolt.manifest;

import java.util.Objects;
import sh.zolt.project.ZoltVersion;

/** One exact expected Zolt version from {@code [toolchain.zolt]}. */
public record ZoltVersionPin(String value) {
    public ZoltVersionPin {
        Objects.requireNonNull(value, "Zolt version pin must not be null.");
        new ZoltVersion(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
