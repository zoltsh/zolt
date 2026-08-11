package sh.zolt.license;

/** A syntactically invalid or catalog-unknown SPDX license expression. */
public final class SpdxExpressionParseException extends IllegalArgumentException {
    public SpdxExpressionParseException(String message) {
        super(message);
    }
}
