package sh.zolt.workspace.state;

import java.util.Map;

/**
 * The opt-in switch that makes workspace input tracking hash every file every command.
 *
 * <p>Normal operation trusts a recorded content hash whenever the file's size, modification time,
 * and file key are unchanged and the row is behind the state file's racy-clean fence. That trust has
 * one residual: a content edit that leaves the size identical <em>and</em> forges a modification
 * time older than the recorded one defeats a metadata comparison, because nothing the filesystem
 * reports has moved. It is the same residual git's index carries, and it does not arise from
 * ordinary editing — only from a deliberate timestamp rewrite or a restore that back-dates files.
 *
 * <p>Setting {@code ZOLT_WORKSPACE_PARANOID=1} removes it: every tracked file is read and hashed,
 * and the recorded metadata is refreshed but never believed. CI runners that restore caches with
 * rewritten timestamps are the intended user.
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
