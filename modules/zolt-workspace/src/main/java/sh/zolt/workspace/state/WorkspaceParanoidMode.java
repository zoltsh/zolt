package sh.zolt.workspace.state;

import java.util.Map;

/**
 * The opt-in switch that makes workspace input tracking hash every file every command.
 *
 * <p>Normal operation trusts a recorded content hash whenever the file's size, modification time,
 * and file key are unchanged and the row is behind the state file's racy-clean fence. The comparison
 * demands the modification time <em>equal</em> the recorded one, so that trust has one residual: a
 * content edit that keeps the size identical, keeps the same file key (editing in place reuses the
 * inode), <em>and</em> forges the modification time back to exactly the recorded value. Nothing the
 * filesystem reports has then moved; an older or a newer time is caught. It is the same residual
 * git's index carries, and it does not arise from ordinary editing — only from a deliberate timestamp
 * rewrite or a cache restore that reproduces the recorded timestamp exactly.
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
