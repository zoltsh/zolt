package sh.zolt.project;

/**
 * How a mediated dependency version conflict is treated (design §9.11). {@link #RESOLVE} mediates
 * silently, {@link #WARN} mediates and emits a structured warning, and {@link #FAIL} rejects the
 * resolution. The engine keeps all three because {@code warn} would otherwise be indistinguishable
 * from {@code resolve}.
 */
public enum VersionConflictPolicy {
    RESOLVE("resolve"),
    WARN("warn"),
    FAIL("fail");

    private final String configValue;

    VersionConflictPolicy(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }
}
