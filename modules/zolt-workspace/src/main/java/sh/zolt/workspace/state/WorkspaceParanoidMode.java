package sh.zolt.workspace.state;

import java.util.Map;

/**
 * Compatibility parser for the historical workspace verification switch.
 * Mutable workspace inputs are now always hashed on every command, regardless of this setting.
 */
public final class WorkspaceParanoidMode {
    public static final String PARANOID_ENV = "ZOLT_WORKSPACE_PARANOID";

    private WorkspaceParanoidMode() {
    }

    public static boolean enabled() {
        return enabled(System.getenv());
    }

    public static boolean enabled(Map<String, String> environment) {
        String value = environment.get(PARANOID_ENV);
        return value != null && (value.equals("1") || value.equalsIgnoreCase("true"));
    }
}
