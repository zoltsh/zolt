package sh.zolt.license;

/** Classifies a declared license string before evidence or policy fallback is applied. */
public enum DeclaredLicenseSyntax {
    VALID_TERM,
    VALID_EXPRESSION,
    UNSUPPORTED_ATOMIC,
    MALFORMED_SPDX,
    PROSE
}
