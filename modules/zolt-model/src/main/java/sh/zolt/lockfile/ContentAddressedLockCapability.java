package sh.zolt.lockfile;

import sh.zolt.error.ActionableError;
import sh.zolt.error.ActionableException;

/** Capability gate for lock consumers that materialize artifact cache paths. */
public final class ContentAddressedLockCapability {
    public static final int MINIMUM_VERSION = 6;

    private ContentAddressedLockCapability() {}

    /** Older locks remain readable, but their Maven-layout paths must not be reinterpreted. */
    public static boolean supportsArtifactCachePaths(ZoltLockfile lockfile) {
        return lockfile.version() >= MINIMUM_VERSION;
    }

    /** Refuses legacy cache paths before a locked resolve performs cache or network work. */
    public static void requireArtifactCachePaths(ZoltLockfile lockfile, String migrationCommand) {
        if (supportsArtifactCachePaths(lockfile)) {
            return;
        }
        throw new ActionableException(ActionableError.of(
                "zolt.lock version "
                        + lockfile.version()
                        + " predates the version "
                        + MINIMUM_VERSION
                        + " content-addressed artifact cache path contract required by this Zolt.",
                "Run `"
                        + migrationCommand
                        + "` once with this Zolt version to migrate zolt.lock, then retry the command."));
    }
}
