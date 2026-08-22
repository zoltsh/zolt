package sh.zolt.manifest;

import java.util.Objects;
import sh.zolt.license.SpdxExpression;
import sh.zolt.license.SpdxExpressionParser;

/** One canonically spelled SPDX license identifier or {@code LICENSE WITH EXCEPTION} term. */
public record SpdxLicenseTerm(String value) implements Comparable<SpdxLicenseTerm> {
    private static final SpdxExpressionParser SPDX = new SpdxExpressionParser();

    public SpdxLicenseTerm {
        Objects.requireNonNull(value, "SPDX license term must not be null.");
        SpdxExpression parsed = SPDX.parseTerm(value);
        if (!parsed.canonical().equals(value)) {
            throw new IllegalArgumentException(
                    "SPDX license term `" + value + "` is not canonical; use `"
                            + parsed.canonical() + "`.");
        }
    }

    public SpdxExpression expression() {
        return SPDX.parseTerm(value);
    }

    @Override
    public int compareTo(SpdxLicenseTerm other) {
        return ManifestModelValues.CODE_POINT_ORDER.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
