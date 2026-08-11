package sh.zolt.sbom;

/** Lifecycle state of one configured scoped license exception. */
public enum LicenseExceptionAuditStatus {
    USED("used"),
    VERSION_MISMATCHED("version-mismatched"),
    MISSING("missing"),
    REDUNDANT("redundant");

    private final String jsonValue;

    LicenseExceptionAuditStatus(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String jsonValue() {
        return jsonValue;
    }
}
