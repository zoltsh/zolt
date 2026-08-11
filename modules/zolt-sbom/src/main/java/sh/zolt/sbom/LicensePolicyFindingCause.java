package sh.zolt.sbom;

/** Structured reason for a license-policy decision, independent of rendered diagnostic text. */
public enum LicensePolicyFindingCause {
    PERMITTED(0),
    SCOPED_EXCEPTION(1),
    UNRECOGNIZED(2),
    ALLOW_LIST(3),
    GLOBAL_DENY(4);

    private final int precedence;

    LicensePolicyFindingCause(int precedence) {
        this.precedence = precedence;
    }

    public int precedence() {
        return precedence;
    }
}
