package sh.zolt.manifest;

/** Authored cache behavior for an exec generated step. */
public enum GeneratedCachePolicy {
    CONTENT("content"),
    NONE("none");

    private final String configValue;

    GeneratedCachePolicy(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }
}
